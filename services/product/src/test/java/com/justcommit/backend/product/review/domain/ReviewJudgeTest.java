package com.justcommit.backend.product.review.domain;

import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.banned.domain.ScientificNameMatcher;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;
import com.justcommit.backend.product.review.domain.enums.ReviewDecision;
import com.justcommit.backend.product.review.domain.enums.SaleForm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// 「결과별 처리」 12줄 (docs/flows/image-review.md). 외부 API 없이 결과 값만 넣어 판정
class ReviewJudgeTest {

    // 금지 목록 일부: 종 금지(나도풍란 + 옛 학명), 변종 금지(단양쑥부쟁이)
    private final ScientificNameMatcher banned = new ScientificNameMatcher(List.of(
            new ScientificNameMatcher.Entry("Sedirea japonica", "Sedirea japonica", "나도풍란"),
            new ScientificNameMatcher.Entry("Phalaenopsis japonica", "Sedirea japonica", "나도풍란"),
            new ScientificNameMatcher.Entry("Aster altaicus var. uchiyamae", "Aster altaicus var. uchiyamae", "단양쑥부쟁이")));

    private final SpeciesMatcher species = new SpeciesMatcher(List.of(
            new SpeciesMatcher.Entry("몬스테라", "Monstera deliciosa", List.of()),
            new SpeciesMatcher.Entry("호접란", "Phalaenopsis hybrids", List.of("팔레놉시스"))));

    private final ReviewJudge judge = new ReviewJudge(banned::match, species);

    private static final ModerationResult CLEAN = new ModerationResult(false, false);
    private static final BannedTextMatcher.Match NO_WORD = new BannedTextMatcher.Match(BannedTextMatcher.Result.NONE, null);
    private static final SellerSpecies MONSTERA = SellerSpecies.of("Monstera deliciosa");

    private static PlantIdentification plant(Candidate... candidates) {
        return new PlantIdentification(true, SaleForm.PLANT, List.of(candidates));
    }

    private static Candidate c(String scientificName, double confidence) {
        return new Candidate(scientificName, null, confidence);
    }

    private static final PlantIdentification MONSTERA_PHOTO = plant(c("Monstera deliciosa", 0.95));

    @Test
    @DisplayName("1. 사진이 유해하면 HARMFUL_IMAGE 반려")
    void harmfulImage() {
        ReviewVerdict v = judge.judge(new ModerationResult(true, true), MONSTERA_PHOTO, NO_WORD, MONSTERA);
        assertThat(v.rejectReason()).isEqualTo(RejectReason.HARMFUL_IMAGE);   // 글도 유해지만 위 줄이 먼저
    }

    @Test
    @DisplayName("2. 제목 · 설명이 유해하면 INAPPROPRIATE_TEXT 반려")
    void harmfulText() {
        ReviewVerdict v = judge.judge(new ModerationResult(false, true), MONSTERA_PHOTO, NO_WORD, MONSTERA);
        assertThat(v.rejectReason()).isEqualTo(RejectReason.INAPPROPRIATE_TEXT);
    }

    @Test
    @DisplayName("3. 식물이 아니면 NOT_PLANT 반려")
    void notPlant() {
        ReviewVerdict v = judge.judge(CLEAN, new PlantIdentification(false, null, List.of()), NO_WORD, MONSTERA);
        assertThat(v.rejectReason()).isEqualTo(RejectReason.NOT_PLANT);
    }

    @Test
    @DisplayName("4. 씨앗 · 삽수 · 구근이면 PROHIBITED_FORM 반려")
    void prohibitedForm() {
        PlantIdentification seeds = new PlantIdentification(true, SaleForm.SEED, List.of(c("Monstera deliciosa", 0.9)));
        ReviewVerdict v = judge.judge(CLEAN, seeds, NO_WORD, MONSTERA);
        assertThat(v.rejectReason()).isEqualTo(RejectReason.PROHIBITED_FORM);
    }

    @Test
    @DisplayName("5. 1순위가 금지 품종이고 확신도 0.7 이상이면 BANNED_SPECIES 반려 (옛 학명도)")
    void bannedTopCandidate() {
        ReviewVerdict v = judge.judge(CLEAN, plant(c("Phalaenopsis japonica", 0.7)), NO_WORD,
                SellerSpecies.of("Phalaenopsis hybrids"));
        assertThat(v.rejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        assertThat(v.detail()).isEqualTo("나도풍란");
    }

    @Test
    @DisplayName("6. 제목 · 설명에 금지어가 있으면 BANNED_SPECIES 반려")
    void bannedWord() {
        BannedTextMatcher.Match word = new BannedTextMatcher.Match(BannedTextMatcher.Result.BANNED, "나도풍란");
        ReviewVerdict v = judge.judge(CLEAN, MONSTERA_PHOTO, word, MONSTERA);
        assertThat(v.rejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        assertThat(v.detail()).isEqualTo("나도풍란");
    }

    @Nested
    @DisplayName("7. 금지 의심 · 관리자 확인 단어는 관리자 확인")
    class Suspected {

        @Test
        @DisplayName("1순위 금지인데 0.7 미만")
        void topBelowThreshold() {
            assertManual(judge.judge(CLEAN, plant(c("Sedirea japonica", 0.69)), NO_WORD, MONSTERA), ManualReason.BANNED_SUSPECTED);
        }

        @Test
        @DisplayName("2 · 3순위가 금지")
        void lowerCandidateBanned() {
            PlantIdentification p = plant(c("Monstera deliciosa", 0.6), c("Sedirea japonica", 0.3));
            assertManual(judge.judge(CLEAN, p, NO_WORD, MONSTERA), ManualReason.BANNED_SUSPECTED);
        }

        @Test
        @DisplayName("2 · 3순위 금지는 확신도가 낮아도 의심 (11/13 실측: 위장 등록이 0.10~0.25로만 들어옴)")
        void lowerCandidateLowConfidence() {
            PlantIdentification p = plant(c("Monstera deliciosa", 0.9), c("Sedirea japonica", 0.1));
            assertManual(judge.judge(CLEAN, p, NO_WORD, MONSTERA), ManualReason.BANNED_SUSPECTED);
        }

        @Test
        @DisplayName("2 · 3순위에 금지 품종이 없으면 통과")
        void lowerCandidateNotBanned() {
            PlantIdentification p = plant(c("Monstera deliciosa", 0.9), c("Monstera adansonii", 0.1));
            assertThat(judge.judge(CLEAN, p, NO_WORD, MONSTERA).decision()).isEqualTo(ReviewDecision.APPROVED);
        }

        @Test
        @DisplayName("변종만 금지인데 후보가 종까지만 일치 (확신도가 높아도)")
        void speciesOnly() {
            assertManual(judge.judge(CLEAN, plant(c("Aster altaicus", 0.9)), NO_WORD, MONSTERA), ManualReason.BANNED_SUSPECTED);
        }

        @Test
        @DisplayName("제목 · 설명에 관리자 확인 단어")
        void reviewWord() {
            BannedTextMatcher.Match word = new BannedTextMatcher.Match(BannedTextMatcher.Result.REVIEW, "자생지");
            ReviewVerdict v = judge.judge(CLEAN, MONSTERA_PHOTO, word, MONSTERA);
            assertManual(v, ManualReason.REVIEW_KEYWORD);
            assertThat(v.detail()).isEqualTo("자생지");
        }
    }

    @Test
    @DisplayName("8. 판매자가 \"기타\" 품종을 고르면 관리자 확인")
    void otherSpecies() {
        assertManual(judge.judge(CLEAN, MONSTERA_PHOTO, NO_WORD, SellerSpecies.otherSpecies()), ManualReason.OTHER_SPECIES);
    }

    @Test
    @DisplayName("9. 후보가 없거나 1순위가 0.3 미만이면 관리자 확인")
    void lowConfidence() {
        assertManual(judge.judge(CLEAN, plant(), NO_WORD, MONSTERA), ManualReason.LOW_CONFIDENCE);
        assertManual(judge.judge(CLEAN, plant(c("Monstera deliciosa", 0.29)), NO_WORD, MONSTERA), ManualReason.LOW_CONFIDENCE);
    }

    @Test
    @DisplayName("10. 품종이 속까지 같으면 통과")
    void approved() {
        assertThat(judge.judge(CLEAN, MONSTERA_PHOTO, NO_WORD, MONSTERA).decision()).isEqualTo(ReviewDecision.APPROVED);
        // 같은 속 다른 종도 일치
        assertThat(judge.judge(CLEAN, plant(c("Monstera adansonii", 0.8)), NO_WORD, MONSTERA).decision())
                .isEqualTo(ReviewDecision.APPROVED);
    }

    @Test
    @DisplayName("11. 품종이 다르면 관리자 확인")
    void mismatch() {
        ReviewVerdict v = judge.judge(CLEAN, plant(c("Ficus elastica", 0.9)), NO_WORD, MONSTERA);
        assertManual(v, ManualReason.SPECIES_MISMATCH);
        assertThat(v.detail()).isEqualTo("Ficus elastica");
    }

    @Nested
    @DisplayName("12. 외부 API 오류 · 해석 불가면 관리자 확인 (자동 통과 · 자동 반려 없음)")
    class EngineError {

        @Test
        @DisplayName("Haiku 실패")
        void plantFailed() {
            assertManual(judge.judge(CLEAN, null, NO_WORD, MONSTERA), ManualReason.ENGINE_ERROR);
        }

        @Test
        @DisplayName("Moderation 실패: 품종이 맞아도 통과시키지 않음")
        void moderationFailed() {
            assertManual(judge.judge(null, MONSTERA_PHOTO, NO_WORD, MONSTERA), ManualReason.ENGINE_ERROR);
        }

        @Test
        @DisplayName("Haiku가 실패해도 다른 결과로 정해지는 반려는 그대로")
        void rejectWithoutEngine() {
            assertThat(judge.judge(new ModerationResult(true, false), null, NO_WORD, MONSTERA).rejectReason())
                    .isEqualTo(RejectReason.HARMFUL_IMAGE);
            BannedTextMatcher.Match word = new BannedTextMatcher.Match(BannedTextMatcher.Result.BANNED, "나도풍란");
            assertThat(judge.judge(null, null, word, MONSTERA).rejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        }
    }

    @Nested
    @DisplayName("글만 수정: 글 유해 검사 · 금지어 대조만")
    class TextOnly {

        @Test
        void harmful() {
            assertThat(judge.judgeText(true, NO_WORD).rejectReason()).isEqualTo(RejectReason.INAPPROPRIATE_TEXT);
        }

        @Test
        void bannedWord() {
            BannedTextMatcher.Match word = new BannedTextMatcher.Match(BannedTextMatcher.Result.BANNED, "나도풍란");
            assertThat(judge.judgeText(false, word).rejectReason()).isEqualTo(RejectReason.BANNED_SPECIES);
        }

        @Test
        void reviewWord() {
            BannedTextMatcher.Match word = new BannedTextMatcher.Match(BannedTextMatcher.Result.REVIEW, "산채");
            assertManual(judge.judgeText(false, word), ManualReason.REVIEW_KEYWORD);
        }

        @Test
        void moderationFailed() {
            assertManual(judge.judgeText(null, NO_WORD), ManualReason.ENGINE_ERROR);
        }

        @Test
        void clean() {
            assertThat(judge.judgeText(false, NO_WORD).decision()).isEqualTo(ReviewDecision.APPROVED);
        }
    }

    private static void assertManual(ReviewVerdict v, ManualReason reason) {
        assertThat(v.decision()).isEqualTo(ReviewDecision.MANUAL);
        assertThat(v.manualReason()).isEqualTo(reason);
    }
}
