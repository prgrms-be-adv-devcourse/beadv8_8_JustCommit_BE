package com.justcommit.backend.ai.species.application;

import com.justcommit.backend.ai.species.domain.SpeciesProfile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

// 품종 프로필 → 임베딩할 품종 문서 (품종명 · 학명 · 유통명 · 배치 장소 · 광도 · 물주기 · 온도 · 특징 · 관리 팁)
// 빈 항목은 줄째 뺀다. 해시에 임베딩 모델 · 차원을 넣어 모델을 바꾸면 전부 다시 임베딩되게 한다
@Component
public class SpeciesDocumentFactory {

    private final String embeddingVersion;

    public SpeciesDocumentFactory(
            @Value("${spring.ai.openai.embedding.model}") String model,
            @Value("${spring.ai.openai.embedding.dimensions}") int dimensions) {
        this.embeddingVersion = model + ":" + dimensions;
    }

    public SpeciesDocument create(SpeciesProfile p) {
        List<String> lines = new ArrayList<>();
        add(lines, "품종", p.name());
        add(lines, "학명", p.scientificName());
        add(lines, "속", p.genus());
        add(lines, "유통명", p.distributionName());
        add(lines, "영어 이름", p.englishName());
        add(lines, "과", p.familyName());
        add(lines, "분류", p.categoryNames());
        add(lines, "잎 색", p.leafColorNames());
        add(lines, "관리 난이도", difficultyName(p.difficulty()));
        add(lines, "광도", p.lightNames());
        add(lines, "배치 장소", p.placementNames());
        add(lines, "생육 온도", p.growthTempName());
        add(lines, "겨울 최저 온도", p.winterMinTemp() == null ? null : p.winterMinTemp() + "℃");
        add(lines, "습도", p.humidityName());
        add(lines, "물주기 봄", p.waterSpring());
        add(lines, "물주기 여름", p.waterSummer());
        add(lines, "물주기 가을", p.waterAutumn());
        add(lines, "물주기 겨울", p.waterWinter());
        add(lines, "특징", p.features());
        add(lines, "관리 팁", join(p.specialCare(), p.advice()));
        add(lines, "병충해", p.pests());
        add(lines, "독성", p.toxicity());

        String text = String.join("\n", lines);
        return new SpeciesDocument(p.speciesId(), p.name(), text, sha256(embeddingVersion + "\n" + text));
    }

    private static void add(List<String> lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(label + ": " + value.strip().replaceAll("\\s+", " "));
        }
    }

    private static String join(String a, String b) {
        boolean hasA = a != null && !a.isBlank();
        boolean hasB = b != null && !b.isBlank();
        if (hasA && hasB) {
            return a.strip() + " " + b.strip();
        }
        return hasA ? a : b;
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
