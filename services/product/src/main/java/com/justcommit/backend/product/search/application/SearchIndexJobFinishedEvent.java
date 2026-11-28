package com.justcommit.backend.product.search.application;

/**
 * 상품 검색 색인 작업이 끝남 (완료 · 실패 · 죽은 작업 실패 처리, #563). 서버 안 스프링 이벤트이고 Kafka로 나가지 않는다.
 *
 * <p>작업 결과를 다루는 곳은 {@link SearchIndexJobTracker}에서 이 이벤트를 내는 한 곳뿐이다.
 * 배치 결과 Slack 알림(#638)은 이 이벤트를 구독해 붙인다.
 */
public record SearchIndexJobFinishedEvent(SearchIndexJobStatus status) {
}
