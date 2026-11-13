package com.ai.ai_chat_client.util;

import com.ai.ai_chat_client.service.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class RagEval {

    @Autowired private VectorStore vectorStore;
    @Autowired private ChatClient ragChatClient;   // 带记忆的主模型（回答用）
    @Autowired private ChatClient rewriter;         // 评判员 / 改写器
    @Autowired private RagService ragService;

    @Value("${app.rag.top-k}") private Integer topK;
    @Value("${app.rag.similarity-threshold}") private double threshold;

    // —— 0) 黄金集：问题 → 期望命中文件 → 期望答案关键词 ——
    record Case(String q, String expectSource, List<String> ansKeys) {}
    private final List<Case> cases = List.of(
            new Case("智聊 AI 有哪些核心功能？",                  "产品介绍.md",   List.of("智能对话","文档问答","写作辅助","智能体编排")),
            new Case("智聊 AI 支持哪些客户端平台？",              "产品介绍.md",   List.of("Windows","macOS","Web","移动端")),
            new Case("会员可以无理由退款吗？",                    "退款政策.md",   List.of("7个自然日","未实际使用","全额退款")),
            new Case("退款一般多久能到账？",                      "退款政策.md",   List.of("1—3","3—7","工作日")),
            new Case("标准会员一个月多少钱？",                    "会员与计费.md", List.of("29","元","月")),
            new Case("免费版每天有多少对话额度？",                "会员与计费.md", List.of("20","免费版","额度")),
            new Case("忘记密码了怎么办？",                        "账号与安全.md", List.of("忘记密码","重置","手机号")),
            new Case("怎么注销账号？",                            "账号与安全.md", List.of("注销账号","15天","冷静期")),
            new Case("上传文档支持哪些格式？",                    "常见问题.md",   List.of("PDF","Word","Markdown","txt")),
            new Case("如何把智聊 AI 接入自己的系统？",            "常见问题.md",   List.of("API","MCP","协议"))
    );

    // —— 1) 结果载体（自动序列化成 JSON 给前端）——
    public record EvalCaseResult(
            String question, String expectSource,
            boolean hybridRecallHit, boolean vectorRecallHit,
            boolean faithful, String answer
    ) {}

    public record EvalReport(
            int total,
            double hybridRecall, double vectorRecall, double faithfulness,
            List<EvalCaseResult> details
    ) {}

    // 缓存最近一次结果，供 /eval/result 直接返回，避免重复跑模型
    private EvalReport lastReport;

    // —— 2) 主入口：跑完整评测并返回结构化报告 ——
    public EvalReport run() {
        List<EvalCaseResult> details = new ArrayList<>();

        Function<String, List<Document>> vectorOnly = q -> vectorStore.similaritySearch(
                SearchRequest.builder().query(q).topK(topK).similarityThreshold(threshold).build());
        Function<String, List<Document>> hybrid = ragService::hybridRetrieve;

        for (Case c : cases) {
            boolean hybridHit = hitAtK(hybrid, topK, c);
            boolean vectorHit = hitAtK(vectorOnly, topK, c);
            String answer   = askOnce(c.q, "eval-" + c.q.hashCode());
            boolean faithful = isFaithful(
                    ragService.hybridRetrieve(c.q).stream().limit(topK)
                            .map(Document::getText).collect(Collectors.joining("\n")),
                    answer);
            details.add(new EvalCaseResult(c.q, c.expectSource, hybridHit, vectorHit, faithful,
                    answer.length() > 500 ? answer.substring(0, 500) : answer));
        }

        long hy = details.stream().filter(EvalCaseResult::hybridRecallHit).count();
        long ve = details.stream().filter(EvalCaseResult::vectorRecallHit).count();
        long fa = details.stream().filter(EvalCaseResult::faithful).count();
        EvalReport report = new EvalReport(cases.size(),
                (double) hy / cases.size(), (double) ve / cases.size(), (double) fa / cases.size(), details);
        this.lastReport = report;

        // 控制台也保留一份，方便 CI / 离线查看
        System.out.printf("=== RagEval === 混合召回=%.0f%% 纯向量=%.0f%% 忠实度=%.0f%%%n",
                report.hybridRecall() * 100, report.vectorRecall() * 100, report.faithfulness() * 100);
        return report;
    }

    // 兼容你原来 /runCompare 的调用：直接触发 run()
    public void runCompare() { run(); }

    public EvalReport lastReport() { return lastReport; }

    // —— 3) 召回判定：期望文件是否进前 K ——
    private boolean hitAtK(Function<String, List<Document>> retriever, int k, Case c) {
        return retriever.apply(c.q).stream().limit(k)
                .anyMatch(d -> c.expectSource.equals(d.getMetadata().getOrDefault("source", "")));
    }

    // —— 4) 忠实度：judge 模型判 YES/NO ——
    private boolean isFaithful(String context, String answer) {
        String v = rewriter.prompt()
                .system("你只回答 YES 或 NO。给定【上下文】，【回答】是否完全由上下文事实支撑、没有任何编造？")
                .user("【上下文】\n" + context + "\n\n【回答】\n" + answer)
                .call().content();
        return v.trim().toUpperCase().startsWith("YES");
    }

    // —— 5) 非流式拿答案（自测用）——
    private String askOnce(String q, String convId) {
        String context = ragService.hybridRetrieve(q).stream()
                .map(d -> "【" + d.getMetadata().getOrDefault("source", "?") + "】\n" + d.getText())
                .collect(Collectors.joining("\n\n"));
        return ragChatClient.prompt()
                .system("你是『智聊 AI』客服，只依据知识库回答，不知道就说不知道。\n\n" + context)
                .user(q)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .call().content();
    }
}
