package com.ai.ai_chat_client.loader;


import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.markdown.MarkdownDocumentReader;
import org.springframework.ai.reader.markdown.config.MarkdownDocumentReaderConfig;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Component
public class KbLoader implements ApplicationRunner {

    @Value("${app.kb.location}")
    private String location;


    private final VectorStore vectorStore;
    private final ResourcePatternResolver resolver;
    public KbLoader(VectorStore vectorStore, ResourcePatternResolver resolver) {
        this.vectorStore = vectorStore;
        this.resolver = resolver;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Resource[] resources = resolver.getResources(location);
        List<Document> list = new ArrayList<>();
        int idx = 0;

        for (Resource resource : resources) {
            String name = resource.getFilename();
            MarkdownDocumentReader reader = new MarkdownDocumentReader(resource, MarkdownDocumentReaderConfig.defaultConfig());
            List<Document> documents =
            TokenTextSplitter.builder()
                    .withChunkSize(800)
                    .withMinChunkLengthToEmbed(120)
                    .withKeepSeparator(true)
                    .withMaxNumChunks(10000)
                    .build()
                    .apply(reader.get());
            for (Document chunk : documents) {
                Map<String, Object> meta = new HashMap<>(chunk.getMetadata());
                meta.put("source", name); // 来源文件名，供 /retrieve 引用
                // 确定性 ID：同名同序 = 同一行 → 幂等 upsert，重启不新增重复
                String id = UUID.nameUUIDFromBytes(
                        (name + "#" + idx++).getBytes(StandardCharsets.UTF_8)).toString();
                list.add(new Document(id, chunk.getText(), meta));
            }
        }
        vectorStore.add(list);

    }
}
