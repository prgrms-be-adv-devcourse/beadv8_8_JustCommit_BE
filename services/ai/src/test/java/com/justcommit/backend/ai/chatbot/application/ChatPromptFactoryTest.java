package com.justcommit.backend.ai.chatbot.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.justcommit.backend.ai.chatbot.application.ChatSearchResult.ProductHit;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatPromptFactoryTest {

    private final ChatPromptFactory factory = new ChatPromptFactory();

    @Test
    @DisplayName("맞는 상품이 없으면 「판매 중 상품 없음」 뒤에 대안 상품을 <alternative>로 구분해 넣는다")
    void rendersAlternativesWhenNoProducts() {
        ChatSearchResult result = new ChatSearchResult(List.of(), List.of(),
                List.of(hit(1L, "산세베리아 중품"), hit(2L, "스투키 소품")));

        String user = factory.create("개업 선물로 뭐가 좋아?", List.of(), result).userMessage();

        assertThat(user).contains(
                "<products>질문과 비슷한 판매 중 상품이 없습니다.</products>",
                "<alternatives>질문과 딱 맞지는 않지만 가장 가까운 판매 중 상품입니다.",
                "<alternative title=\"산세베리아 중품\" price=\"20000원\">",
                "<alternative title=\"스투키 소품\" price=\"20000원\">",
                "</alternatives>",
                "질문: 개업 선물로 뭐가 좋아?");
        assertThat(user).doesNotContain("<product title=");
    }

    @Test
    @DisplayName("맞는 상품이 있으면 <product>로 넣고 대안 블록은 넣지 않는다")
    void rendersProductsWithoutAlternatives() {
        ChatSearchResult result = new ChatSearchResult(List.of(hit(1L, "그늘 몬스테라")), List.of(), List.of());

        String user = factory.create("어두운 방 식물", List.of(), result).userMessage();

        assertThat(user).contains("<product title=\"그늘 몬스테라\" price=\"20000원\">")
                .doesNotContain("<alternatives>", "판매 중 상품이 없습니다");
    }

    @Test
    @DisplayName("시스템 규칙: 상황 질문은 식물 질문으로 보고, 일반 상식 한 문장 + 판매 중이 아니면 대안 추천, 관계없는 질문엔 대안을 권하지 않는다")
    void systemRulesCoverSituationQuestions() {
        assertThat(ChatPromptFactory.SYSTEM).contains(
                "개업 · 집들이 선물, 인테리어, 키울 장소처럼 식물을 고르는 상황 질문도 식물 질문으로 보고 답합니다.",
                "일반 상식 한 문장",
                "replant에는 지금 그 품종이 판매되고 있지 않아요",
                "대신 판매 중인 ~를 추천해 드려요",
                "<product>나 <alternative>에 있으면 「판매 중이 아니다」라고 하지 말고 그 상품을 바로 권합니다.",
                "관계없는 질문은 식물 질문만 도울 수 있다고 정중히 답하고, <alternative>를 권하지 않습니다.",
                "관리법 · 효능 · 독성 · 가격 · 재고 · 배송 같은 사실은 검색 결과 안에서만 말합니다.",
                "2~3문장");
    }

    static ProductHit hit(long id, String title) {
        return new ProductHit(id, 218L, "기타", title, BigDecimal.valueOf(20000), "https://img.test/" + id + ".jpg",
                title + " 문서", 0.1);
    }
}
