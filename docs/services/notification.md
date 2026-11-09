# notification 서비스

> 언제 읽나: notification 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-09 · 관련: Miro 5 · 10, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 알림 저장 | 주문 · 수락 · 배송 · 취소 · 반품 · 구매 확정 · 정산 · 검수 반려 이벤트를 받아 알림 생성 (`event_key`로 한 번만) |
| 실시간 전달 | SSE로 안 읽은 수 전송 (탭 여러 개 모두 수신) |
| 조회 · 읽음 | 목록(`?read=` 필터, 커서 페이지네이션) · 안 읽은 수 · 읽음 · 모두 읽음. 삭제 없음 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `NOTIFICATION` | 알림 (`event_key` UK, `(member_id, is_read, created_at)` 인덱스, 지우지 않고 보관, Flyway `V1__create_notification.sql`) |

## 외부 API

`notifications` (`?read=`) · `notifications/{id}` · `notifications/unread-count` · `notifications/subscribe` (SSE) → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

없음

## 호출하는 내부 API

| 대상 | 용도 | 상태 |
|---|---|---|
| market | 알림 문구용 배송 정보 (`GET /internal/v1/shipments?sellerOrderIds=`) | 🟨 제안 |

## 발행 이벤트

없음

## 구독 이벤트

| 토픽 | 받아서 하는 일 | 멱등 처리 |
|---|---|---|
| `market.events.v1` | 수락 요청 · 수락 · 거절 · 자동 거절 · 취소 · 구매 확정 · 반품 알림 | `event_key` UK |
| `market.events.v1` `ShipmentStatusChanged` | 배송 상태 · 반품 수거 알림 | `event_key` UK |
| `settlement.events.v1` | 정산 완료 알림 | `event_key` UK |
| `product.events.v1` `ProductRejected` | 검수 반려 알림 (사유 포함) | `event_key` UK |

회원 이벤트는 구독하지 않습니다.

## 스케줄러

SSE 하트비트(30초) → [스케줄러 표](../reference/schedulers.md)

## 정책

노션 「정책 정리」 9절 기준입니다.

| 받는 사람 | 알림 |
|---|---|
| 판매자 | "주문이 들어왔어요. 수락해 주세요" · 24시간 무응답 자동 거절 · 택배 예약 · 수거 · 배송 중 · 배송 완료 · 주문 취소 · 반품 신청 · 반품 48시간 자동 승인 · 반품 수거(1번) · 검수 반려(사유) |
| 구매자 | 판매자 수락 · 판매자 거절 · 응답이 없어 자동 취소(거절과 구분, 일부 거절은 1건으로 묶음) · 수거 · 배송 중 · 배송 완료 · 자동 구매 확정 · 반품 거절 · 반품 환불 완료 |
| 보내지 않음 | 본인이 직접 취소한 건, 판매자 마감 전 재알림 |

SSE: Gateway 경로 타임아웃 끔, Nginx 1시간, 30초 ping.

## 테스트 시나리오

S-A07 · S-H02 ~ S-H04 · S-I01 ~ S-I04 → [테스트 시나리오](../testing/scenarios.md)
