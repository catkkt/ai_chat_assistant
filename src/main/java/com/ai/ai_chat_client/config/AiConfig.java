package com.ai.ai_chat_client.config;


import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Value("${app.rag.top-k}")
    private Integer topK;


    @Value("${app.rag.similarity-threshold}")
    private double similarityThreshold;


    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .maxMessages(10)
                .chatMemoryRepository(chatMemoryRepository)
                .build();
    }


    @Bean
    public ChatClient ragChatClient(ChatMemory chatMemory, ChatModel chatModel, VectorStore vectorStore) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是『智聊 AI』的客服助手，只依据提供的知识库回答，不知道就说不知道。")
                .defaultAdvisors(
//                        多轮记忆
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
//                        向量化检索
                        QuestionAnswerAdvisor.builder(vectorStore)
                                .searchRequest(SearchRequest.builder()
                                        .topK(topK)
                                        .similarityThreshold(similarityThreshold)
                                        .build()).build(),
                        new SimpleLoggerAdvisor()

                ).build();
    }

    @Bean
    public ChatClient rewriter(ChatModel chatModel) {
        return ChatClient.builder(chatModel)
                .defaultSystem("你是检索 query 优化器。把用户问题改写成 2 个利于向量检索的同义问法，每行一个，不要序号、不要解释。")
                .build();
    }
}
