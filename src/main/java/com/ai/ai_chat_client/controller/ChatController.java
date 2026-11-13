package com.ai.ai_chat_client.controller;


import com.ai.ai_chat_client.entity.SourceSnippet;
import com.ai.ai_chat_client.service.RagService;
import com.ai.ai_chat_client.util.RagEval;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
public class ChatController {

    @Autowired
    private RagService ragService;

    @Autowired
    private RagEval ragEval;

    @GetMapping(value = "/ask", produces = "text/html;charset=utf-8")
    public Flux<String> chat(String msg,
                             String convId) {
//        return ragService.ask(msg,convId);
        return ragService.askEnhanced(msg, convId);
    }

    @GetMapping(value = "/retrieve")
    public List<SourceSnippet> retrieve(String msg) {
        return ragService.retrieveSource(msg);
    }

    // 触发评测并返回结果（会逐条调模型，约 30–60 秒）
    @GetMapping(value = "/eval/run")
    public RagEval.EvalReport evalRun() {
        return ragEval.run();
    }

    // 直接返回最近一次评测结果（不重跑），供面板首屏展示
    @GetMapping(value = "/eval/result")
    public RagEval.EvalReport evalResult() {
        return ragEval.lastReport();
    }
}
