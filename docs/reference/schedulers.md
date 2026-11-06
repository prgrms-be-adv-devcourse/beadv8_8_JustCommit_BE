# 스케줄러

> 언제 읽나: @Scheduled 작업의 주기 · 역할을 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro A4

## 작업 표

| 작업 | 서비스 | 주기 | 종류 | 도구 | 하는 일 |
|---|---|---|---|---|---|
| 결제 대기 만료 | market | 30초 | – | SKIP LOCKED | 10분 지난 `PAYMENT_PENDING` 주문 만료 · 예약 해제 |
| 수락 자동 거절 | market | 10분 | – | SKIP LOCKED | 24시간 지난 `ACCEPT_PENDING` 상품 거절 → 전액 환불 |
| 반품 자동 승인 | market | 10분 | – | SKIP LOCKED | 48시간 무응답 반품 신청 승인 |
| 자동 구매 확정 | market | 1시간 | – | SKIP LOCKED | 배송 완료 72시간 지난 `NORMAL` 상품 확정 (반품 신청 상품 제외) |
| 택배 예약 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `READY` 배송(반품 포함) 예약. 건별 `next_attempt_at` 10초 → 30초 → 1분 고정 |
| 택배 취소 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `CANCEL_PENDING` 배송 취소 재요청 |
| 상품 해제 · 판매완료 재시도 | market | 10초 | 끝까지 | SKIP LOCKED | 실패한 product 내부 호출 다시 보내기 |
| Saga 복구 | market | 1분 | 확인 후 포기 | SKIP LOCKED | 멈춘 주문을 같은 멱등 키로 확인, 5회 실패하면 `REVIEW_REQUIRED` |
| 요금표 버전 대사 | market | 하루 1번 | – | ShedLock | 404-express 요금표 버전과 비교, 다르면 새로 받음 |
| 관리자 확인 지연 | product | ⬜ | – | SKIP LOCKED | 12시간 지나면 관리자에게 묶어서 알림, 그 뒤 4시간 지나면 `REVIEW_EXPIRED` 자동 반려 (⬜ 노션 안 기한 표기 차이) |
| 토스 결과 불명 조회 | payment | ⬜ | 확인 후 포기 | SKIP LOCKED | `PENDING` 충전 조회 3회 → `REVIEW_REQUIRED` |
| 토스 대사 | payment | 하루 1번 | – | ShedLock | 토스 거래와 내부 충전 기록 대조, 잔액을 줄여야 하는 불일치는 알림만 |
| 정산 지급 큐 | settlement | 10초 | 끝까지 | SKIP LOCKED | `PENDING` 정산을 건별로 payment에 지급 (`settlementId` 멱등) |
| Outbox 재전송 | Outbox 가진 서비스 전부 | 10초 | 5회 후 `FAILED` | SKIP LOCKED | 미발행 이벤트 다시 보내기, 발행 완료 행 7일 뒤 삭제 |
| SSE 하트비트 | notification | 30초 | – | – | 연결 유지 ping |

## 주의

| 규칙 | 내용 |
|---|---|
| 여러 파드 | Gateway · market은 파드가 2개라 스케줄러도 두 번 돈다. DB 행을 처리하는 작업은 `FOR UPDATE SKIP LOCKED`로 나눠 가져가고, 행이 아닌 한 번만 할 일은 ShedLock으로 한 인스턴스만 실행 |
| 끝까지 | 멱등이라 계속 보내도 안전 → 포기하지 않음. 알림은 건마다가 아니라 "오래 남은 건이 있다"는 상태 기준으로 한 번 (예: 10분 넘게 `READY`) |
| 확인 후 포기 | 정한 횟수 뒤 `REVIEW_REQUIRED` (Saga 복구 5회, 토스 조회 3회) |
| ai-service | 파드가 늘었다 줄어 스케줄러를 두지 않음 |
