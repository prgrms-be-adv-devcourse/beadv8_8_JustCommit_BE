# 이벤트 규칙

> 언제 읽나: Kafka 이벤트를 발행하거나 구독할 때
> 상태: ⬜ 작성 전 · 마지막 수정 2026-11-04 · 관련: Miro 8 · 11-1 · 11-2, 노션 기획안 「이벤트 규칙」

## 이름과 토픽

<!-- 채울 내용: 과거형 이벤트 이름, 도메인 토픽 .v1, Key = Aggregate ID -->

## 메시지 형식

<!-- 채울 내용: EventEnvelope 필드, traceparent 헤더, JSON -->

## Outbox

<!-- 채울 내용: 모든 이벤트 Outbox, 커밋 후 바로 발행 + 재전송 스케줄러, outbox_event 표 -->

## 소비와 멱등

<!-- 채울 내용: Inbox 없음, 업무 키로 멱등, 서비스별 Consumer Group -->

## 실패 처리

<!-- 채울 내용: @RetryableTopic 1·2·4초, @DltHandler, 토픽-dlt, Slack 알림, 순서 주의 -->
