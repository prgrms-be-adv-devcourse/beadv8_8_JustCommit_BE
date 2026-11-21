package com.justcommit.backend.ai.chatbot.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * 질문 벡터로 찾은 근거: 판매 중 상품 · 품종 문서 (유사도 높은 순).
 * alternatives는 products가 0개일 때만 채우는 「가장 가까운 판매 중 상품」(유사도 하한 없음). 선물 · 장소 같은 상황 질문에
 * 맞는 상품이 없을 때 LLM이 대신 권할 후보이고, 화면 카드에는 답변에 제목이 나온 것만 넣는다
 */
public record ChatSearchResult(List<ProductHit> products, List<SpeciesHit> species, List<ProductHit> alternatives) {

    public record ProductHit(long productId, long speciesId, String speciesName, String title, BigDecimal price,
            String thumbnailUrl, String document, double similarity) {
    }

    public record SpeciesHit(int speciesId, String name, String document, double similarity) {
    }
}
