
package com.jh.han.rag.rag.embedding;

import com.jh.han.rag.infra.embedding.EmbeddingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

@Slf4j
@SpringBootTest
@RequiredArgsConstructor(onConstructor = @__(@Autowired))
public class SiliconFlowEmbeddingServiceTests {

    private final EmbeddingService embeddingService;

    @Test
    public void embeddingSiliconFlow() {
        List<Float> embedded = embeddingService.embed("测试向量描述");
        System.out.println(embedded);
    }
}
