package com.ai.ai_chat_client.service;


import com.ai.ai_chat_client.entity.SourceSnippet;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class RagService {

    @Autowired
    private VectorStore vectorStore;

    @Autowired
    private ChatClient ragChatClient;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatClient rewriter;

    @Value("${app.rag.top-k}")
    private Integer topK;


    @Value("${app.rag.similarity-threshold}")
    private double similarityThreshold;


    public Flux<String> ask(String message, String convId) {

        return ragChatClient
                .prompt()
                .user(message)
                .advisors(a ->a.param(ChatMemory.CONVERSATION_ID, convId))
                .stream()
                .content();

////        创建对话
//        return chatClientBuilder.build()
//                .prompt()
//                .user(message)
////                向量化检索
//                .advisors(QuestionAnswerAdvisor.builder(vectorStore)
//                        .searchRequest(SearchRequest.builder()
//                                .topK(topK)
//                                .similarityThreshold(similarityThreshold)
//                                .build())
//                        .build())
////                流式返回
//                .stream()
//                .content();
    }


    public Flux<String> askEnhanced(String message, String convId){

        List<Document> docs = hybridRetrieve(message);
        String context = docs.stream()
                .map(d -> "【" + d.getMetadata().getOrDefault("source", "?") + "】\n" + d.getText())
                .collect(Collectors.joining("\n\n"));

        return ragChatClient.prompt()
                .system("你是『智聊 AI』客服，只依据下面的知识片段回答；片段后用【文件名】标注来源，不知道就说不知道。\n\n" + context)
                .user(message)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .stream().content();
    }

//    混合检索
    public List<Document> hybridRetrieve(String message) {
        Map<String, Document> docById = new HashMap<>();   // id -> 文档(保留第一份)
        Map<String, Double> scoreById = new HashMap<>();    // id -> 累加的 RRF 分数


        for (String query : enhancedQueries(message)) {

//            语义路
            List<Document> sem = vectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(query)
                            .topK(topK)
                            .similarityThreshold(similarityThreshold).build()
            );

//            词法路
            String sql = "select id,content,metadata " +
                    "FROM vector_store " +
                    "ORDER BY similarity(content, ?) " +
                    "DESC LIMIT ?";
            List<Document> lex = jdbcTemplate.query(
                    sql,
                    (rs, i) -> new Document(
                            rs.getString("id"),
                            rs.getString("content"),
                            parseMeta(rs.getString("metadata"))),
                    query, topK
            );

//            组合，两路累加（rrf算法）
            int r = 1;
            for (Document document : sem) {
                scoreById.merge(document.getId(), 1.0/(r++ + 60),Double::sum);
                docById.putIfAbsent(document.getId(), document);
            }

            r = 1;
            for (Document document : lex) {
                scoreById.merge(document.getId(), 1.0/(r++ + 60),Double::sum);
                docById.putIfAbsent(document.getId(), document);
            }
        }


//        排序并且返回tpok;
        return scoreById.entrySet().stream().sorted((a,b) -> Double.compare(b.getValue(),a.getValue()))
                .limit(topK)
                .map(e -> {
                    Document document = docById.get(e.getKey());
                    document.getMetadata().put("rrf_score",e.getValue());
                    return document;
                })
                .toList();
    }

    private Map<String, Object> parseMeta(String json) {
        if (json == null || json.isBlank()) return Map.of();
        try { return objectMapper.readValue(json,
                new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) { return Map.of(); }
    }



    //    查询改写
    private List<String> enhancedQueries(String message) {
        String variants = rewriter.prompt()
                .system("你是检索优化器。把用户问题改写成 2 个利于向量检索的同义问法，每行一个，不要序号、不要解释。")
                .user(message)
                .call()
                .content();
        List<String> list = new ArrayList<>();

//        原来内容不变
        list.add(message);

        Arrays.stream(variants.split("\n"))
                .map(String::trim)
                .filter(s ->!s.isEmpty()).forEach(list::add);

        return list;
    }


    //    来源片段返回（引用返回）
    public List<SourceSnippet> retrieveSource(String message) {

        List<SourceSnippet> list = hybridRetrieve(message).stream().map(d -> new SourceSnippet(
                (String) d.getMetadata().getOrDefault("source", "unknown"),
                d.getText().length() > 200 ? d.getText().substring(0, 200) + "..."
                        : d.getText()
        )).toList();
//        List<Document> documents = vectorStore.similaritySearch(
//                SearchRequest.builder().query(message)
//                        .topK(topK)
//                        .similarityThreshold(similarityThreshold)
//                        .build());
//        List<SourceSnippet> list = documents.stream().map(d -> new SourceSnippet(
//                (String) d.getMetadata().getOrDefault("source", "unknown"),
//                d.getText().length() > 200 ? d.getText().substring(0, 200) + "..."
//                        : d.getText()
//        )).toList();
        return list;
    }


}
