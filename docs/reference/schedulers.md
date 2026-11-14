# 스케줄러

> 언제 읽나: 스케줄러(`@Scheduled`) 작업의 주기 · 역할, 재시도 간격, 중복 실행 방지(SKIP LOCKED · ShedLock)를 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-14 · 관련: Miro F-7, 노션 「정책 정리」 1.4 · 11.5

## 작업 표

서비스별 스케줄러(`@Scheduled`) 작업 목록입니다. 이 표의 「주기」가 원본이고, 업무 기한(10분 · 24시간 등)은 각 서비스 카드 「정책」이 원본입니다.

| 작업 | 서비스 | 주기 | 종류 | 도구 | 하는 일 |
|---|---|---|---|---|---|
| 결제 대기 만료 | market | 30초 | – | SKIP LOCKED | 뒷정리만: 기한 지난 결제 대기(`PAYMENT_PENDING`) 주문 만료 · `market.order-expired.v1` 발행. 기한 확인은 결제할 때 market이 직접 함(`PAYMENT_WINDOW_EXPIRED`). 결제 결과를 모르는 주문은 제외 (기한 → [market 정책](../services/market.md#정책)) |
| 수락 자동 거절 | market | 10분 | – | SKIP LOCKED | 기한 지난 `ACCEPT_PENDING` 주문상품 자동 거절 → 전액 환불 (기한 → [market 정책](../services/market.md#정책)) |
| 반품 자동 승인 | market | 10분 | – | SKIP LOCKED | 기한 안에 판매자가 응답하지 않은 반품 신청 자동 승인 (기한 → [market 정책](../services/market.md#정책)) |
| 자동 구매 확정 | market | 1시간 | – | SKIP LOCKED | 기한 지난 `NORMAL` 주문상품 구매 확정, 반품 신청한 주문상품 제외 (기한 → [market 정책](../services/market.md#정책)) |
| 택배 예약 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `READY` 배송(반품 포함) 택배 예약. 실패하면 다음 시도까지 10초 → 30초 → 1분 고정 (건별 `next_attempt_at`) |
| 택배 취소 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `CANCEL_PENDING` 배송 취소 재요청 |
| Saga 복구 | market | 1분 | 확인 후 포기 | SKIP LOCKED | 결과 불명 결제(orderId) · 취소 · 환불(orderItemId · sellerOrderId)을 같은 멱등 키로 다시 요청, 5회 안 되면 `REVIEW_REQUIRED` → [주문 · 결제](../flows/order-payment.md#멈춘-주문-복구) |
| 요금표 버전 대사 | market | 매일 09:30 (EC2가 켜진 시간) | – | ShedLock | 택배사(404-express) 요금표 버전과 비교, 다르면 새로 받음 |
| 검수 엔진 오류 재처리 | product | 5분 | 확인 후 포기 (3회 → 관리자 확인) | SKIP LOCKED | `IMAGE_REVIEW`가 `ENGINE_ERROR`인 건과 30분이 지나도 `REQUESTED`인 건(멈춘 검수 요청)을 같은 검수 버전으로 다시 검수, 3회 모두 실패하면 관리자 확인(`ENGINE_ERROR`) → [사진 검수](../flows/image-review.md#엔진-오류-재처리) |
| 관리자 확인 지연 | product | ⬜ | – | SKIP LOCKED | 관리자 확인으로 간 지 12시간 지나면 관리자에게 묶어서 알림, 그 뒤 12시간(모두 24시간) 지나면 `REVIEW_EXPIRED` 자동 반려. 엔진 오류 재처리 시간은 세지 않음 |
| 토스 결과 불명 조회 | payment | ⬜ | 확인 후 포기 | SKIP LOCKED | `PENDING` 충전 조회 3회 → `REVIEW_REQUIRED` |
| 토스 대사 | payment | 하루 1번 (⬜ 실행 시각) | – | ShedLock | 토스 거래와 내부 충전 기록 대조, 잔액을 늘리는 보정(토스 승인 · 내부 미승인)은 자동. ⬜ 잔액을 줄여야 하는 불일치 처리 |
| 정산 지급 큐 | settlement | 10초 | 끝까지 | SKIP LOCKED | `PENDING` 정산을 건별로 payment에 지급 (`settlementId` 멱등) |
| Outbox 재전송 | member · product · market · settlement (payment는 ⬜ 발행 이벤트 없음) | 10초 | 확인 후 포기 (5회 → `FAILED`) | SKIP LOCKED | 미발행 이벤트 다시 보내기, 발행 완료 행 7일 뒤 삭제 |
| SSE 하트비트 | notification | 30초 | – | – | 연결 유지 ping |

## 주의

스케줄러를 여러 파드에서 돌릴 때와 「종류」 칸의 뜻입니다.

| 규칙 | 내용 |
|---|---|
| 여러 파드 | market은 파드가 2개라 스케줄러도 두 번 돈다 (Gateway는 스케줄러 없음). DB 행을 처리하는 작업은 `FOR UPDATE SKIP LOCKED`로 나눠 가져가고, 행이 아닌 한 번만 할 일은 ShedLock으로 한 인스턴스만 실행 |
| 끝까지 | 멱등이라 계속 보내도 안전 → 포기하지 않음 (택배 예약 · 취소, 정산 지급). 알림은 건마다가 아니라 "오래 남은 건이 있다"는 상태 기준으로 한 번 (예: 10분 넘게 `READY`). 상품 판매 완료 · 예약 해제는 이벤트라 스케줄러 없이 Outbox 재전송 · 소비 재시도 · DLT를 따름 |
| 확인 후 포기 | 정한 횟수 뒤 `REVIEW_REQUIRED` (Saga 복구 5회, 토스 조회 3회) · Outbox는 5회 뒤 `FAILED` + 팀 알림 · 검수 엔진 오류는 3회 뒤 관리자 확인 |
| – | 매 주기 그때 대상인 행만 처리하고, 재시도 개념이 없음 |
| ai-service | 파드가 늘었다 줄어 스케줄러를 두지 않음 |
