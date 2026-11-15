package com.justcommit.backend.product.messaging;

import com.justcommit.backend.common.messaging.EventEnvelope;
import com.justcommit.backend.common.messaging.EventEnvelopeMapper;
import com.justcommit.backend.common.messaging.KafkaTopics;
import com.justcommit.backend.product.product.event.ProductReviewRequested;
import com.justcommit.backend.product.review.application.ImageReviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

// product.review.v1 (자기 토픽) → 사진 검수
// 같은 이벤트가 두 번 와도 image_review의 같은 버전 행이 REQUESTED일 때만 검수하므로 결과가 같음
// 외부 API 실패는 서비스 안에서 엔진 오류 재처리 · 관리자 확인으로 끝나고, DB 오류 같은 예외만 공통 재시도 · DLT로 감
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductReviewListener {

    static final String PRODUCT_REVIEW_REQUESTED = "ProductReviewRequested";

    private final EventEnvelopeMapper mapper;
    private final ImageReviewService imageReviewService;

    // 한 건이 외부 API로 최대 수 분 걸릴 수 있어 한 번에 한 건만 가져옴 (여러 건을 몰아 받으면 max.poll.interval.ms 5분을 넘김)
    @KafkaListener(topics = "${PRODUCT_REVIEW_TOPIC:" + KafkaTopics.PRODUCT_REVIEW + "}",
            properties = "max.poll.records=1")
    public void onReviewRequested(EventEnvelope envelope) {
        if (!PRODUCT_REVIEW_REQUESTED.equals(envelope.eventType())) {
            log.warn("모르는 검수 이벤트 무시: eventId={}, eventType={}", envelope.eventId(), envelope.eventType());
            return;
        }
        ProductReviewRequested event = mapper.readPayload(envelope, ProductReviewRequested.class);
        log.info("사진 검수 시작: eventId={}, productId={}, reviewVersion={}",
                envelope.eventId(), event.productId(), event.reviewVersion());
        imageReviewService.review(event.productId(), event.reviewVersion());
    }
}
