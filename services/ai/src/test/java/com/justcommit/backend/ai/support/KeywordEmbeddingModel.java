package com.justcommit.backend.ai.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * OpenAI 대신 쓰는 임베딩 모델. 글에 든 낱말로 축을 정해, 「어두운」과 「그늘」처럼 같은 축 낱말이면 가깝게 만든다.
 * 실제 의미 검색 품질이 아니라 검색 · 필터 · 저장 흐름을 보기 위한 것. 부른 문장 수를 세고, 실패를 흉내 낼 수 있다.
 */
public class KeywordEmbeddingModel implements EmbeddingModel {

    public static final int DIMENSIONS = 1536;
    private static final Map<String, Integer> AXES = Map.of(
            "그늘", 1, "어두운", 1, "햇빛 적은", 1,
            "햇빛 많은", 2, "건조", 2, "선인장", 2);

    public final AtomicInteger embeddedTexts = new AtomicInteger();
    public final AtomicBoolean failing = new AtomicBoolean();

    public float[] vectorOf(String text) {
        float[] vector = new float[DIMENSIONS];
        vector[0] = 0.1f;   // 아무 낱말도 없으면 이 축만 → 다른 글과 유사도가 낮음
        AXES.forEach((word, axis) -> {
            if (text.contains(word)) {
                vector[axis] = 1f;
            }
        });
        return vector;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        if (failing.get()) {
            throw new IllegalStateException("임베딩 API 실패 (테스트)");
        }
        List<String> texts = request.getInstructions();
        embeddedTexts.addAndGet(texts.size());
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < texts.size(); i++) {
            embeddings.add(new Embedding(vectorOf(texts.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return call(new EmbeddingRequest(List.of(document.getText()), null)).getResult().getOutput();
    }

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }
}
