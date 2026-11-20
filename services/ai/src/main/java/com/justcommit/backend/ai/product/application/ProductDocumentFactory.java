package com.justcommit.backend.ai.product.application;

import com.justcommit.backend.ai.product.domain.ProductSnapshot;
import com.justcommit.backend.ai.product.infrastructure.ProductVectorRepository.SpeciesSummary;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 상품 스냅샷 → 임베딩할 상품 문서 (#298): 제목 · 품종명 · 품종 특징 요약 · 설명 · 키우기 메모 · 크기
// 품종 특징(광도 · 배치 장소 · 난이도 등)을 넣어 「그늘」처럼 상품 글에 없는 말로도 찾게 한다.
// 상태 · 숨김 · 가격은 넣지 않는다 → 상태만 바뀐 이벤트는 해시가 같아 임베딩 모델을 부르지 않음
@Component
public class ProductDocumentFactory {

    // 품종 특징 원문은 길어서 앞부분만 (상품 문서가 품종 설명에 묻히지 않게)
    static final int SPECIES_FEATURES_MAX = 200;

    private final String embeddingVersion;

    public ProductDocumentFactory(
            @Value("${custom.embedding.model}") String model,
            @Value("${custom.embedding.dimensions}") int dimensions) {
        this.embeddingVersion = model + ":" + dimensions;
    }

    public record ProductDocument(long productId, String text, String hash) {
    }

    public ProductDocument create(ProductSnapshot p, SpeciesSummary species) {
        List<String> lines = new ArrayList<>();
        add(lines, "상품", p.title());
        if (species != null) {
            add(lines, "품종", species.scientificName() == null
                    ? species.name() : species.name() + " (" + species.scientificName() + ")");
            add(lines, "관리 난이도", difficultyName(species.difficulty()));
            add(lines, "광도", species.lightNames());
            add(lines, "배치 장소", species.placementNames());
            add(lines, "생육 온도", species.growthTempName());
            add(lines, "물주기", species.waterSpring());
            add(lines, "품종 특징", truncate(species.features(), SPECIES_FEATURES_MAX));
        }
        add(lines, "설명", p.description());
        add(lines, "키우기 메모", p.growNote());
        add(lines, "크기", size(p.heightCm(), p.potSize()));

        String text = String.join("\n", lines);
        return new ProductDocument(p.productId(), text, sha256(embeddingVersion + "\n" + text));
    }

    private static String size(Integer heightCm, String potSize) {
        List<String> parts = new ArrayList<>();
        if (heightCm != null) {
            parts.add("높이 " + heightCm + "cm");
        }
        if (potSize != null && !potSize.isBlank()) {
            parts.add("화분 " + potSize.strip());
        }
        return String.join(", ", parts);
    }

    private static void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value.strip().replaceAll("\\s+", " "));
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() <= max ? stripped : stripped.substring(0, max) + "…";
    }

    private static String difficultyName(String difficulty) {
        if (difficulty == null) {
            return null;
        }
        return switch (difficulty) {
            case "EASY" -> "쉬움 (초보자)";
            case "NORMAL" -> "보통 (경험자)";
            case "HARD" -> "어려움 (전문가)";
            default -> null;
        };
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
