package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.enums.TaxonRank;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// 엔진이 답한 학명이 판매 금지 목록에 걸리는지 (속 · 종 · 변종 단위)
// - 속 금지: 그 속이면 모두 금지 (예: Erythroxylum coca)
// - 종 금지: 그 종과 아래 변종 금지. 속만 같으면 통과 (예: 호접란 Phalaenopsis 통과, Phalaenopsis japonica 금지)
// - 변종 금지: 그 변종만 금지. 후보가 종까지만 같으면 판단 불가 → 관리자 확인
// 비교 단위는 이름의 단어 수로 정한다(1 = 속, 2 = 종, 3 이상 = 변종). 별칭에는 단위 칸이 없어서
public final class ScientificNameMatcher {

    public enum Result { BANNED, SPECIES_ONLY, NONE }

    // 대조 결과. 걸린 금지 품종의 법령 학명 · 한글 이름 (NONE이면 null)
    public record Match(Result result, String bannedScientificName, String koreanName) {
        static final Match NONE = new Match(Result.NONE, null, null);
    }

    // 대조할 이름 하나 (법령 학명이나 학명 동의어) + 그 이름이 가리키는 금지 품종
    public record Entry(String name, String bannedScientificName, String koreanName) {
    }

    private final List<Named> entries;

    public ScientificNameMatcher(List<Entry> entries) {
        this.entries = entries.stream()
                .map(e -> new Named(ParsedName.of(e.name()), e))
                .filter(n -> n.parsed() != null)
                .toList();
    }

    public Match match(String candidate) {
        ParsedName c = ParsedName.of(candidate);
        if (c == null) {
            return Match.NONE;
        }
        Match speciesOnly = null;
        for (Named n : entries) {
            ParsedName b = n.parsed();
            switch (b.rank()) {
                case GENUS -> {
                    if (c.genus().equals(b.genus())) return banned(n);
                }
                case SPECIES -> {
                    if (b.species().equals(c.species())) return banned(n);
                }
                case INFRA -> {
                    if (b.infra().equals(c.infra())) return banned(n);
                    // 후보가 종까지만 답했고 그 종의 변종이 금지 → 금지인지 알 수 없음
                    if (c.rank() == TaxonRank.SPECIES && b.species().equals(c.species()) && speciesOnly == null) {
                        speciesOnly = new Match(Result.SPECIES_ONLY, n.entry().bannedScientificName(), n.entry().koreanName());
                    }
                }
            }
        }
        return speciesOnly != null ? speciesOnly : Match.NONE;
    }

    private static Match banned(Named n) {
        return new Match(Result.BANNED, n.entry().bannedScientificName(), n.entry().koreanName());
    }

    private record Named(ParsedName parsed, Entry entry) {
    }

    // 소문자 · 공백 하나로 맞춘 학명을 속 · 종 · 변종 열쇠로 나눔
    // 변종 열쇠는 "속 종 마지막단어"라 var. · subsp. · f. 표기가 달라도 같은 변종으로 봄
    // 교잡종 기호(× 또는 단독 x)는 단어로 세지 않음 → "Sedirea ×japonica" · "Sedirea x japonica"도 종으로 봄
    private record ParsedName(TaxonRank rank, String genus, String species, String infra) {

        static ParsedName of(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            String[] t = Arrays.stream(name.toLowerCase(Locale.ROOT).replace('×', ' ').trim().split("\\s+"))
                    .filter(w -> !w.isEmpty() && !w.equals("x"))
                    .toArray(String[]::new);
            if (t.length == 0) {
                return null;
            }
            if (t.length == 1) {
                return new ParsedName(TaxonRank.GENUS, t[0], null, null);
            }
            String species = t[0] + " " + t[1];
            if (t.length == 2) {
                return new ParsedName(TaxonRank.SPECIES, t[0], species, null);
            }
            return new ParsedName(TaxonRank.INFRA, t[0], species, species + " " + t[t.length - 1]);
        }
    }
}
