# notification 서비스

> 언제 읽나: notification 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 5 · 10, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 알림 저장 | 주문 · 배송 · 취소 · 구매 확정 · 환불 · 정산 · 사진 검수 거절 이벤트를 받아 알림 생성 |
| 실시간 전달 | SSE로 안 읽은 수 전송 (탭 여러 개 모두 수신) |
| 조회 · 읽음 | 목록 · 안 읽은 수 · 읽음 · 모두 읽음 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `NOTIFICATION` | 알림 (eventKey UK) |

## 외부 API

`notifications` · `notifications/{id}` · `notifications/unread-count` · `notifications/subscribe` (SSE) → [외부 API 목록](../reference/external-apis.md)

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
| `order.events.v1` | 결제 · 취소 · 구매 확정 · 환불 알림 | eventKey UK |
| `shipment.events.v1` `ShipmentStatusChanged` | 배송 상태 알림 | eventKey UK |
| `settlement.events.v1` | 정산 완료 알림 | eventKey UK |
| `product.events.v1` `ProductRejected` | 사진 검수 거절 알림 (판매자) | eventKey UK |

회원 이벤트는 구독하지 않음 (회원 복제본 없음)

## 스케줄러

SSE 하트비트(30초) → [스케줄러 표](../reference/schedulers.md)

## 정책

| 정책 | 내용 |
|---|---|
| 받는 사람 | 상대가 한 일만 알림. 구매자가 직접 취소하면 구매자에게는 알림을 보내지 않음 |
| SSE 연결 | Gateway 경로 타임아웃 끔, Nginx 1시간, 30초 ping |

## 테스트 시나리오

S-I01 ~ S-I03 → [테스트 시나리오](../testing/scenarios.md)
