package com.justcommit.backend.product.review.domain;

import com.justcommit.backend.product.banned.domain.BannedTextMatcher;
import com.justcommit.backend.product.banned.domain.ScientificNameMatcher;
import com.justcommit.backend.product.review.domain.enums.ManualReason;
import com.justcommit.backend.product.review.domain.enums.RejectReason;

import java.util.List;
import java.util.function.Function;

// 「결과별 처리」 표 (docs/flows/image-review.md). 위에서부터 처음 해당하는 줄에서 정해진다
// 외부 API 결과(moderation · plant)가 null이면 그 API가 재시도 후에도 실패했거나 응답을 해석하지 못한 것
// → 외부 결과 없이도 정할 수 있는 반려(유해 · 금지어)까지만 보고 관리자 확인. 장애로 자동 통과 · 자동 반려하지 않음
public final class ReviewJudge {

    public static final double BANNED_CONFIDENCE = 0.7;   // 1순위 금지 품종을 바로 반려하는 확신도
    public static final double MIN_CONFIDENCE = 0.3;      // 이보다 낮은 후보는 믿지 않음

    private final Function<String, ScientificNameMatcher.Match> bannedLookup;   // 학명 → 금지 목록 대조
    private final SpeciesMatcher speciesMatcher;

    public ReviewJudge(Function<String, ScientificNameMatcher.Match> bannedLookup, SpeciesMatcher speciesMatcher) {
        this.bannedLookup = bannedLookup;
        this.speciesMatcher = speciesMatcher;
    }

    // 사진 검수 (등록 · 사진이나 품종 수정)
    public ReviewVerdict judge(ModerationResult moderation, PlantIdentification plant,
                               BannedTextMatcher.Match text, SellerSpecies seller) {
        // 1 · 2) 유해 사진 · 글
        if (moderation != null && moderation.imageFlagged()) {
            return ReviewVerdict.reject(RejectReason.HARMFUL_IMAGE, null);
        }
        if (moderation != null && moderation.textFlagged()) {
            return ReviewVerdict.reject(RejectReason.INAPPROPRIATE_TEXT, null);
        }
        if (plant != null) {
            // 3) 식물이 아님
            if (!plant.plant()) {
                return ReviewVerdict.reject(RejectReason.NOT_PLANT, null);
            }
            // 4) 씨앗 · 삽수 · 구근
            if (plant.saleForm() != null && plant.saleForm().isProhibited()) {
                return ReviewVerdict.reject(RejectReason.PROHIBITED_FORM, plant.saleForm().name());
            }
            // 5) 1순위가 금지 품종, 확신도 0.7 이상
            Candidate top = top(plant);
            if (top != null && top.confidence() >= BANNED_CONFIDENCE) {
                ScientificNameMatcher.Match match = bannedLookup.apply(top.scientificName());
                if (match.result() == ScientificNameMatcher.Result.BANNED) {
                    return ReviewVerdict.reject(RejectReason.BANNED_SPECIES, match.koreanName());
                }
            }
        }
        // 6) 제목 · 설명에 금지어
        if (text.result() == BannedTextMatcher.Result.BANNED) {
            return ReviewVerdict.reject(RejectReason.BANNED_SPECIES, text.word());
        }
        // 12) 외부 API 오류 · 해석 불가 (여기서부터는 엔진 결과가 있어야 정할 수 있음)
        if (moderation == null || plant == null) {
            return ReviewVerdict.manual(ManualReason.ENGINE_ERROR, null);
        }
        // 7) 금지 의심 · 관리자 확인 단어
        String suspected = suspectedBanned(plant.candidates());
        if (suspected != null) {
            return ReviewVerdict.manual(ManualReason.BANNED_SUSPECTED, suspected);
        }
        if (text.result() == BannedTextMatcher.Result.REVIEW) {
            return ReviewVerdict.manual(ManualReason.REVIEW_KEYWORD, text.word());
        }
        // 8) "기타" 품종
        if (seller.other()) {
            return ReviewVerdict.manual(ManualReason.OTHER_SPECIES, null);
        }
        // 9) 후보 없음 · 1순위 0.3 미만
        Candidate top = top(plant);
        if (top == null || top.confidence() < MIN_CONFIDENCE) {
            return ReviewVerdict.manual(ManualReason.LOW_CONFIDENCE, null);
        }
        // 10 · 11) 품종 일치 → 통과, 불일치 → 관리자 확인
        if (speciesMatcher.matches(top, seller.scientificName())) {
            return ReviewVerdict.approve();
        }
        return ReviewVerdict.manual(ManualReason.SPECIES_MISMATCH, top.scientificName());
    }

    // 글만 수정: 글 유해 검사 · 금지어 대조만 (moderationTextFlagged가 null이면 Moderation 실패)
    public ReviewVerdict judgeText(Boolean moderationTextFlagged, BannedTextMatcher.Match text) {
        if (Boolean.TRUE.equals(moderationTextFlagged)) {
            return ReviewVerdict.reject(RejectReason.INAPPROPRIATE_TEXT, null);
        }
        if (text.result() == BannedTextMatcher.Result.BANNED) {
            return ReviewVerdict.reject(RejectReason.BANNED_SPECIES, text.word());
        }
        if (moderationTextFlagged == null) {
            return ReviewVerdict.manual(ManualReason.ENGINE_ERROR, null);
        }
        if (text.result() == BannedTextMatcher.Result.REVIEW) {
            return ReviewVerdict.manual(ManualReason.REVIEW_KEYWORD, text.word());
        }
        return ReviewVerdict.approve();
    }

    // 7줄의 금지 의심: 1순위 금지인데 0.7 미만(0.7 이상은 5줄에서 반려됨) · 2 · 3순위 금지(0.3 이상)
    // · 변종만 금지인데 후보가 종까지만 일치. 걸린 금지 품종의 한글 이름을 돌려줌
    private String suspectedBanned(List<Candidate> candidates) {
        for (int i = 0; i < candidates.size(); i++) {
            Candidate c = candidates.get(i);
            if (i > 0 && c.confidence() < MIN_CONFIDENCE) {
                continue;
            }
            ScientificNameMatcher.Match match = bannedLookup.apply(c.scientificName());
            if (match.result() != ScientificNameMatcher.Result.NONE) {
                return match.koreanName();
            }
        }
        return null;
    }

    private static Candidate top(PlantIdentification plant) {
        return plant.candidates().isEmpty() ? null : plant.candidates().get(0);
    }
}
