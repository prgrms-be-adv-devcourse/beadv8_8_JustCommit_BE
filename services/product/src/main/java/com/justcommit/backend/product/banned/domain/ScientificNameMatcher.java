package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.enums.TaxonRank;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

// 엔진이 답한 학명이 판매 금지 목록에 걸리는지 (속 · 종 · 변종 단위)
// - 속 금지: 그 속이면 모두 금지 (예: Erythroxylum coca)
// - 종 금지: 그 종과 아래 변종 금지. 속만 같으면 통과 (예: 호접란 Phalaenopsis 통과, Phalaenopsis japonica 금지)
// - 변종 금지: 그 변종만 금지. 후보가 종까지만 같으면 판단 불가 → 관리자 확인
// 비교 단위는 이름을 읽어 정한다(속 · 종 · 변종 표기). 별칭에는 단위 칸이 없어서
// 명명자(예: Kitam., (Rchb.f.) Garay)가 붙어도 무시하고, 변종은 var. · subsp. · f. 뒤 단어나 세 번째 소문자 단어로 읽음
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

    // 학명을 속 · 종 · 변종 열쇠(소문자)로 나눔
    // - 교잡종 기호(× 또는 단독 x)는 단어로 세지 않음 → "Sedirea ×japonica"도 종으로 봄
    // - 종소명 · 변종명은 글자와 하이픈만 있는 단어. 마침표 · 괄호가 있거나 대문자로 시작하는 뒤쪽 단어는 명명자로 보고 무시
    // - 변종 열쇠는 "속 종 변종명"이라 var. · subsp. · f. 표기가 달라도 같은 변종으로 봄
    private record ParsedName(TaxonRank rank, String genus, String species, String infra) {

        private static final Set<String> RANK_WORDS = Set.of("var.", "var", "subsp.", "subsp", "ssp.", "ssp", "f.", "fo.", "forma");

        static ParsedName of(String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            List<String> t = Arrays.stream(name.replace('×', ' ').trim().split("\\s+"))
                    .filter(w -> !w.isEmpty() && !w.equalsIgnoreCase("x"))
                    .toList();
            if (t.isEmpty()) {
                return null;
            }
            String genus = t.get(0).toLowerCase(Locale.ROOT);
            if (t.size() == 1 || !isEpithet(t.get(1))) {
                return new ParsedName(TaxonRank.GENUS, genus, null, null);   // 속 + 명명자도 속으로
            }
            String species = genus + " " + t.get(1).toLowerCase(Locale.ROOT);
            String infra = infraEpithet(t);
            if (infra == null) {
                return new ParsedName(TaxonRank.SPECIES, genus, species, null);
            }
            return new ParsedName(TaxonRank.INFRA, genus, species, species + " " + infra);
        }

        // var. · subsp. · f. 뒤 단어, 없으면 세 번째 단어가 소문자 종소명 꼴일 때만 변종으로 봄 (대문자 · 마침표면 명명자)
        private static String infraEpithet(List<String> t) {
            for (int i = 2; i < t.size() - 1; i++) {
                if (RANK_WORDS.contains(t.get(i).toLowerCase(Locale.ROOT)) && isEpithet(t.get(i + 1))) {
                    return t.get(i + 1).toLowerCase(Locale.ROOT);
                }
            }
            if (t.size() >= 3 && t.get(2).matches("[a-z][a-z-]*")) {
                return t.get(2);
            }
            return null;
        }

        private static boolean isEpithet(String word) {
            return word.matches("[\\p{L}][\\p{L}-]*");
        }
    }
}
