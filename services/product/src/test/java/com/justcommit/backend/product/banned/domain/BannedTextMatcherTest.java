package com.justcommit.backend.product.banned.domain;

import com.justcommit.backend.product.banned.domain.BannedTextMatcher.Result;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BannedTextMatcherTest {

    private final BannedTextMatcher matcher = new BannedTextMatcher(
            List.of("양귀비", "아편 양귀비", "복주머니란", "코카", "풍란", "나도풍란", "삽수", "대마"),
            List.of("자생지", "산채"),
            List.of("꽃양귀비", "개양귀비", "서양복주머니란", "코카콜라"));

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "양귀비 모종 팝니다 | BANNED",
            "아편양귀비 | BANNED",                       // 띄어쓰기 달라도 같게
            "꽃양귀비 모종 팝니다 | NONE",               // 예외 단어
            "개양귀비 씨앗 | NONE",
            "서양복주머니란 분양 | NONE",
            "복주머니란 분양 | BANNED",
            "코카콜라 병에 심은 다육 | NONE",
            "코카 잎 | BANNED",
            "산채 춘란 | REVIEW",                        // 산채는 관리자 확인 단어
            "뿌리가 산 채로 왔어요 | REVIEW",            // 띄어쓰기를 지워 산채로 걸림 → 반려하지 않고 사람이 확인
            "몬스테라 삽수 | BANNED",
            "자생지와 비슷한 환경에서 키웠어요 | REVIEW",
            "몬스테라 분양합니다 | NONE"
    })
    @DisplayName("예외 단어를 먼저 빼고 금지 이름 · 금지어 · 관리자 확인 단어를 찾는다")
    void match(String text, Result expected) {
        assertThat(matcher.match(text, null).result()).isEqualTo(expected);
    }

    @Test
    @DisplayName("사용자가 쓴 구분자 문자(|)도 문장부호처럼 지워 금지어를 놓치지 않는다")
    void userPipeIsRemoved() {
        assertThat(matcher.match("대|마 씨앗", null).result()).isEqualTo(Result.BANNED);
        assertThat(matcher.match("대 마", null).result()).isEqualTo(Result.BANNED);
    }

    @Test
    @DisplayName("제목 끝과 설명 앞은 붙여 읽지 않는다")
    void titleAndDescriptionAreNotJoined() {
        assertThat(matcher.match("분양합니다 대", "마당에서 키웠어요").result()).isEqualTo(Result.NONE);
    }

    @Test
    @DisplayName("예외 단어와 금지 이름이 같이 있으면 금지 이름으로 걸린다")
    void exceptionDoesNotHideOtherBannedWord() {
        assertThat(matcher.match("꽃양귀비 말고 양귀비도 있어요", null).result()).isEqualTo(Result.BANNED);
    }

    @Test
    @DisplayName("제목과 설명을 함께 보고, 더 긴 이름을 결과로 돌려준다")
    void titleAndDescription() {
        BannedTextMatcher.Match match = matcher.match("난 분양", "나도풍란입니다");

        assertThat(match.result()).isEqualTo(Result.BANNED);
        assertThat(match.word()).isEqualTo("나도풍란");
    }
}
