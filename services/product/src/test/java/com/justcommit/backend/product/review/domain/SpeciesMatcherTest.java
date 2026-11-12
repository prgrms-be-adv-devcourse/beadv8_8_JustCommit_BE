package com.justcommit.backend.product.review.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpeciesMatcherTest {

    private final SpeciesMatcher matcher = new SpeciesMatcher(List.of(
            new SpeciesMatcher.Entry("산세베리아", "Dracaena trifasciata", List.of("스투키 산세베리아")),
            new SpeciesMatcher.Entry("골드크레스트 '윌마'", "Cupressus macrocarpa 'Wilma'", List.of("율마"))));

    @Test
    @DisplayName("학명의 속이 같으면 일치 (대소문자 · 품종명 무시)")
    void sameGenus() {
        assertThat(matcher.matches(new Candidate("cupressus macrocarpa", null, 0.9), "Cupressus macrocarpa 'Wilma'")).isTrue();
        assertThat(matcher.matches(new Candidate("Cupressus sempervirens", null, 0.9), "Cupressus macrocarpa 'Wilma'")).isTrue();
    }

    @Test
    @DisplayName("옛 학명이라 속이 달라도 한글 이름 · 별칭으로 찾은 품종의 속이 같으면 일치")
    void byKoreanNameOrAlias() {
        assertThat(matcher.matches(new Candidate("Sansevieria trifasciata", "산세베리아", 0.9), "Dracaena trifasciata")).isTrue();
        assertThat(matcher.matches(new Candidate("Sansevieria cylindrica", "스투키산세베리아", 0.9), "Dracaena trifasciata")).isTrue();
        assertThat(matcher.matches(new Candidate(null, "율마", 0.9), "Cupressus macrocarpa 'Wilma'")).isTrue();
    }

    @Test
    @DisplayName("속도 다르고 이름도 다르면 불일치")
    void mismatch() {
        assertThat(matcher.matches(new Candidate("Ficus elastica", "고무나무", 0.9), "Dracaena trifasciata")).isFalse();
        assertThat(matcher.matches(new Candidate(null, null, 0.9), "Dracaena trifasciata")).isFalse();
    }

    @Test
    @DisplayName("교잡종 기호는 속으로 보지 않음")
    void hybrid() {
        assertThat(SpeciesMatcher.genus("× Phalaenopsis hybrid")).isEqualTo("phalaenopsis");
        assertThat(SpeciesMatcher.genus("x Phalaenopsis")).isEqualTo("phalaenopsis");
    }
}
