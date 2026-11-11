package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.ScientificNameMatcher.Entry;
import com.justcommit.backend.product.banned.domain.ScientificNameMatcher.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScientificNameMatcherTest {

    // V8 데이터 일부: 종 금지(+ 학명 동의어), 속 금지, 변종 금지
    private final ScientificNameMatcher matcher = new ScientificNameMatcher(List.of(
            new Entry("Sedirea japonica", "Sedirea japonica", "나도풍란"),
            new Entry("Phalaenopsis japonica", "Sedirea japonica", "나도풍란"),
            new Entry("Erythroxylum", "Erythroxylum", "코카나무"),
            new Entry("Aster altaicus var. uchiyamae", "Aster altaicus var. uchiyamae", "단양쑥부쟁이")));

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "Phalaenopsis, NONE",                       // 호접란 속은 허용
            "Phalaenopsis amabilis, NONE",              // 같은 속 다른 종은 허용
            "Phalaenopsis japonica, BANNED",            // 나도풍란의 옛 학명
            "Sedirea japonica, BANNED",
            "sedirea   JAPONICA, BANNED",               // 대소문자 · 공백 무시
            "Sedirea japonica var. alba, BANNED",       // 금지 종의 변종도 금지
            "Erythroxylum coca, BANNED",                // 속 금지
            "Erythroxylum, BANNED",
            "Aster altaicus var. uchiyamae, BANNED",
            "Aster altaicus subsp. uchiyamae, BANNED",  // 변종 표기(var. · subsp.)가 달라도 같은 변종
            "Aster altaicus, SPECIES_ONLY",             // 변종 금지인데 종까지만 답함 → 관리자 확인
            "Aster altaicus var. other, NONE",          // 같은 종의 다른 변종은 허용
            "Monstera deliciosa, NONE",
            "Sedirea ×japonica, BANNED",                // 교잡종 기호(×)는 단어로 세지 않음
            "Sedirea x japonica, BANNED",               // 단독 x도 교잡종 기호로 봄
            "Phalaenopsis × hybrida, NONE",             // 호접란 교잡종은 종 금지에 걸리지 않음
            "Aster altaicus var. uchiyamae Kitam., BANNED",       // 명명자가 붙어도 변종으로 읽음
            "Aster altaicus uchiyamae, BANNED",                   // var. 없는 세 단어 이름
            "Aster altaicus Willd., SPECIES_ONLY",                // 명명자는 변종으로 보지 않음
            "Sedirea japonica (Rchb.f.) Garay & H.R.Sweet, BANNED", // 괄호 · 마침표가 있는 명명자 무시
            "Erythroxylum P.Browne, BANNED",                      // 속 + 명명자도 속으로
            "Phalaenopsis Blume, NONE"                            // 호접란 속 + 명명자는 허용
    })
    @DisplayName("속 · 종 · 변종 단위로 대조한다")
    void match(String candidate, Result expected) {
        assertThat(matcher.match(candidate).result()).isEqualTo(expected);
    }

    @Test
    @DisplayName("학명 동의어로 걸려도 결과에는 법령 학명과 한글 이름을 돌려준다")
    void matchReturnsLegalName() {
        ScientificNameMatcher.Match match = matcher.match("Phalaenopsis japonica");

        assertThat(match.bannedScientificName()).isEqualTo("Sedirea japonica");
        assertThat(match.koreanName()).isEqualTo("나도풍란");
    }

    @Test
    @DisplayName("빈 학명은 NONE")
    void blank() {
        assertThat(matcher.match(" ").result()).isEqualTo(Result.NONE);
        assertThat(matcher.match(null).result()).isEqualTo(Result.NONE);
    }
}
