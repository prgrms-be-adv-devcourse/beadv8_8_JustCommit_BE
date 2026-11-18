package com.justcommit.backend.product.review.domain;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

// 엔진 1순위 후보가 판매자가 고른 품종과 같은지 (속까지 같으면 일치)
// 1) 후보 학명의 속 2) 후보 한글 이름 → species.name · species_alias로 찾은 품종의 scientific_name 속
// 둘 중 하나라도 판매자 품종의 속과 같으면 일치. 엔진이 옛 학명(예: Sansevieria)을 답해도 한글 이름(산세베리아)으로 맞출 수 있게
// 속 이름이 바뀐 경우(산세베리아 → 드라세나로 합쳐짐)는 같은 속으로 봄. 엔진이 옛 이름 · 새 이름을 섞어 답함
public final class SpeciesMatcher {

    // 옛 속 → 지금 속. 11/14 실판매 실측에서 스투키 · 산세베리아 13건이 이 차이로만 불일치
    private static final Map<String, String> GENUS_SYNONYMS = Map.of("sansevieria", "dracaena");

    // 품종 하나: 이름 · 학명 · 별칭(유통명)
    public record Entry(String name, String scientificName, List<String> aliases) {
    }

    // 정규화한 한글 이름 · 별칭 → 그 이름을 쓰는 품종들의 속
    private final Map<String, Set<String>> generaByName = new HashMap<>();

    public SpeciesMatcher(List<Entry> entries) {
        for (Entry e : entries) {
            String genus = genus(e.scientificName());
            if (genus == null) {
                continue;
            }
            put(e.name(), genus);
            if (e.aliases() != null) {
                e.aliases().forEach(alias -> put(alias, genus));
            }
        }
    }

    public boolean matches(Candidate candidate, String sellerScientificName) {
        String sellerGenus = genus(sellerScientificName);
        if (candidate == null || sellerGenus == null) {
            return false;
        }
        if (sellerGenus.equals(genus(candidate.scientificName()))) {
            return true;
        }
        String name = normalize(candidate.koreanName());
        return name != null && generaByName.getOrDefault(name, Set.of()).contains(sellerGenus);
    }

    private void put(String name, String genus) {
        String key = normalize(name);
        if (key != null) {
            generaByName.computeIfAbsent(key, k -> new HashSet<>()).add(genus);
        }
    }

    // 학명의 첫 단어(속)를 소문자로, 옛 속 이름은 지금 이름으로. 교잡종 기호(× · 단독 x)는 건너뜀
    static String genus(String scientificName) {
        if (scientificName == null) {
            return null;
        }
        for (String word : scientificName.replace('×', ' ').trim().split("\\s+")) {
            if (!word.isEmpty() && !word.equalsIgnoreCase("x")) {
                String genus = word.toLowerCase(Locale.ROOT);
                return GENUS_SYNONYMS.getOrDefault(genus, genus);
            }
        }
        return null;
    }

    // 띄어쓰기 · 문장부호를 지워 "스킨답서스"와 "스킨 답서스"를 같게 봄
    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return n.isEmpty() ? null : n;
    }
}
