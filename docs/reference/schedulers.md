# 스케줄러

> 언제 읽나: 스케줄러(`@Scheduled`) 작업의 주기 · 역할, 재시도 간격, 중복 실행 방지(SKIP LOCKED · ShedLock)를 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-19 · 관련: Miro F-7, 노션 「정책 정리」 1.4 · 11.5

## 작업 표

서비스별 스케줄러(`@Scheduled`) 작업 목록입니다. 이 표의 「주기」가 원본이고, 업무 기한(10분 · 24시간 등)은 각 서비스 카드 「정책」이 원본입니다.

| 작업 | 서비스 | 주기 | 종류 | 도구 | 하는 일 |
|---|---|---|---|---|---|
| 결제 대기 만료 | market | 30초 | – | SKIP LOCKED | 뒷정리만: 기한 지난 결제 대기(`PAYMENT_PENDING`) 주문 만료 · `market.order-expired.v1` 발행. 기한 확인은 결제할 때 market이 직접 함(`PAYMENT_WINDOW_EXPIRED`). 결제 결과를 모르는 주문은 제외 (기한 → [market 정책](../services/market.md#정책)) |
| 수락 자동 거절 | market | 10분 | – | SKIP LOCKED | 기한 지난 `ACCEPT_PENDING` 주문상품 자동 거절 → 전액 환불 (기한 → [market 정책](../services/market.md#정책)) |
| 반품 자동 승인 | market | 10분 | – | SKIP LOCKED | 기한 안에 판매자가 응답하지 않은 반품 신청 자동 승인 (기한 → [market 정책](../services/market.md#정책)) |
| 자동 구매 확정 | market | 1시간 | – | SKIP LOCKED | 기한 지난 `NORMAL` 주문상품 구매 확정, 반품 신청한 주문상품 제외 (기한 → [market 정책](../services/market.md#정책)) |
| 택배 예약 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `READY` 배송(반품 포함) 택배 예약. 한 번 실행할 때 최대 20건을 한 건씩 확보(다음 시도 시각을 30초 뒤로 미루는 임대)하고, 택배사 호출은 임대 후 트랜잭션 밖에서 함. 실패하면 다음 시도까지 10초 → 30초 → 1분 고정 (건별 `next_attempt_at` · `attempt_count`, 마지막 실패 원인 `last_error`). 결제 · 수락 흐름은 `READY` 행만 만들고 택배사를 부르지 않음 |
| 택배 취소 큐 | market | 10초 | 끝까지 | SKIP LOCKED | `CANCEL_PENDING` 배송을 택배 예약 큐와 같은 방식(최대 20건을 한 건씩 확보 · 30초 임대 · 트랜잭션 밖 호출)으로 택배사에 주문번호 + 누른 시각으로 취소 요청. 결과는 배송 상태(`CANCELLED` · `CANCEL_LOCKED`) + `SHIPMENT_CANCEL_RESULT` 행 한 트랜잭션. 실패하면 10초 → 30초 → 1분 고정 (`next_attempt_at` · `attempt_count` · `last_error`) → [ADR 0026](../decisions/0026-courier-cancel-queue-and-distance-judgment.md) |
| Saga 복구 | market | 1분 | 확인 후 포기 | SKIP LOCKED | 결과 불명 결제(orderId) · 취소 · 환불(orderItemId · sellerOrderId)을 같은 멱등 키로 다시 요청, 5회 안 되면 `REVIEW_REQUIRED`. 결제는 `attempted_at`에서 10초 지난 `PAYMENT_PROCESSING` 주문을 한 번에 최대 50건, 주문마다 선점(`attempt_no`, `attempt_count` +1 커밋) 후 트랜잭션 밖에서 재호출하고, 자기 `attempt_no`일 때만 결과 반영. 이어서 처리 전 택배 취소 결과(`SHIPMENT_CANCEL_RESULT`)를 최대 50건 SKIP LOCKED로 집어 취소됨이면 환불, 취소 불가면 주문상품을 되돌리고 `processed_at` 기록. 마지막으로 `attempted_at`에서 10초 지난 `CANCEL_PROCESSING` 주문상품을 최대 50건, 주문상품마다 선점(`attempt_count` +1 커밋) 후 트랜잭션 밖에서 환불 재요청. 결과 반영은 「취소 중」일 때만. 단계마다 실패해도 다음 단계는 돎. 실행이 끝나면 `REVIEW_REQUIRED` 주문 수 지표를 갱신 → [주문 · 결제](../flows/order-payment.md#멈춘-주문-복구) |
| 요금표 버전 대사 | market | 매일 09:30 (EC2가 켜진 시간) | – | ShedLock | 택배사(404-express) 요금표 버전과 비교, 다르면 새로 받음 |
| 토스 결과 불명 조회 | payment | 5분 | 확인 후 포기 (조회 실패 3회 또는 충전 생성 60분 뒤 미확정 → `REVIEW_REQUIRED`) | SKIP LOCKED | PENDING 충전을 토스 orderId로 조회해 결과 확정. 실행당 최대 100건 처리, 결과에 따라 적립·실패·재조회·관리자 확인으로 분기. 승인 재호출 없음 → 충전 복구 상세 |
| 검수 엔진 오류 재처리 | product | 5분 | 확인 후 포기 (3회 → 관리자 확인) | SKIP LOCKED | `IMAGE_REVIEW`가 `ENGINE_ERROR`인 건과 30분이 지나도 `REQUESTED`인 건(멈춘 검수 요청)을 같은 검수 버전으로 다시 검수, 3회 모두 실패하면 관리자 확인(`ENGINE_ERROR`) → [사진 검수](../flows/image-review.md#엔진-오류-재처리) |
| 관리자 확인 지연 | product | 10분 | – | SKIP LOCKED | 관리자 확인으로 간 지 24시간 지난 건을 `REVIEW_EXPIRED`로 자동 반려(한 번에 최대 50건, 그사이 수정 · 삭제된 상품은 건너뜀). 엔진 오류 재처리 시간은 세지 않음 |
| 관리자 확인 지연 알림 | product | 한국 시간 09~21시 매 정각 (`custom.review.delay-alert.cron`, 기본 `0 0 9-21 * * *`, `Asia/Seoul`) | – | SKIP LOCKED | 관리자 확인으로 간 지 12시간은 지났고 24시간은 아직 안 지났으며 아직 알리지 않은(`delay_notified_at` 없음) 건을 묶어 `ImageReviewDelayed` 1개를 Outbox로 발행하고 같은 트랜잭션에서 알림 시각을 채움 (0건이면 보내지 않음, 같은 건은 한 번만). 24시간이 지나 자동 반려를 기다리는 건은 빼서 반려 스케줄러와 실행 순서에 기대지 않음. 밤에는 관리자 알림을 보내지 않고 밤사이 늦어진 건은 09시에 묶어 보냄 (알림 구간 12~24시간과 밤 공백 21~09시가 모두 12시간이라 빠지는 건 없음, 자동 반려는 밤에도 10분마다). 켜고 끄기는 「관리자 확인 지연」과 같은 `custom.review.deadline.enabled` (#509) |
| 상품 검색 인덱스 준비 | product | 1분 (준비되면 멈춤) | – | – | 앱이 뜰 때 OpenSearch가 꺼져 있어 alias `products` · 인덱스 `products_v1`을 만들지 못했으면 다시 시도. 파드 여러 개가 동시에 만들어도 한쪽만 성공하고 나머지는 이미 있음으로 넘어감 → [검색 · 추천](../flows/search-recommend.md) |
| 토스 대사 | payment | 하루 1번 (⬜ 실행 시각) | – | ShedLock | 토스 거래와 내부 충전 기록 대조, 잔액을 늘리는 보정(토스 승인 · 내부 미승인)은 자동. ⬜ 잔액을 줄여야 하는 불일치 처리 |
| 정산 지급 큐 | settlement | 10초 | 끝까지 | SKIP LOCKED | 지급 대상(`PENDING`, `FAILED`이면서 `next_attempt_at`이 지난) 정산을 건별로 payment에 지급 (`settlementId` 멱등). 기본 10초 간격으로 실행하며, 한 번 실행할 때 최대 50건을 한 건씩 확보(다음 시도 시각을 30초 뒤로 미루는 임대)해 처리. payment 호출은 임대 후 트랜잭션 밖에서 실행. 성공하면 `COMPLETED` + `completed_at`, 실패(연결 실패, 응답 타임아웃, 4xx, 5xx 모두)하면 `FAILED` + `attempt_count` 증가, 다음 시도까지 10초 → 30초 → 1분 고정. 늦게 온 실패는 임대가 그대로일 때만 반영. 10분 넘게 미지급인 정산이 있으면 ERROR 로그 + 지표 `settlement_payouts_delayed`(→ [관측](../infra/observability.md#알림-규칙) 「정산 지급 지연」) |
| 토큰 폐기 재처리 | member | 10초 | 보관 기한까지 재시도 | SKIP LOCKED | Redis 기록을 확인하지 못한 폐기 작업(`PENDING`)을 다시 기록. 기본 10초 간격으로 실행하며, 한 번 실행할 때 최대 50건을 한 건씩 확보(다음 시도 시각을 30초 뒤로 미루는 임대)해 처리. Redis 호출은 임대 후 트랜잭션 밖에서 실행. 실패하면 다음 시도까지 10초 → 30초 → 1분, 보관 기한이 지나면 `EXPIRED` → [ADR 0022](../decisions/0022-token-revocation-and-retry.md) |
| 탈퇴 기록 정리 | member | 1시간 (앱 시작 직후 1번) | – | – | 탈퇴 시각이 30일 지난 탈퇴 기록을 DELETE 한 번으로 삭제. 재가입 제한 판정은 이 작업과 무관(가입할 때 탈퇴 시각으로 직접 판정) → [탈퇴 흐름](../flows/signup-and-replica.md#재가입-제한) |
| 카카오 연결 해제 웹훅 처리 | member | 10초 | 단계별 최초 포함 5회(실패 뒤 10 · 30 · 60 · 120초) → `FAILED` + 재개 단계 저장, 운영자 재개 | SKIP LOCKED | 처리할 수신 기록을 짧은 트랜잭션에서 확보(시도 번호 +1)한 뒤 잠금을 풀고 단계별로 처리: 카카오 연결 상태 조회 → `INACTIVE` + 토큰 폐기 → 자동 탈퇴 대상만 3곳 확인 후 탈퇴 또는 안내 메일. 카카오 · 내부 서비스 호출 · 메일 발송 중에는 잠금을 잡지 않고, 결과 반영은 자기 시도 번호와 현재 상태가 그대로일 때만 → [탈퇴 흐름](../flows/signup-and-replica.md#카카오-연결-해제-웹훅). 설정 `member.kakao-unlink.processing.enabled` · `interval` |
| Outbox 재전송 | member · product · market · settlement (payment는 ⬜ 발행 이벤트 없음) | 10초 | 확인 후 포기 (5회 → `FAILED`) | SKIP LOCKED | 미발행 이벤트 다시 보내기, 발행 완료 행 7일 뒤 삭제. 재전송이 끝나면 가장 오래된 PENDING 나이 · `FAILED` 수 지표(`outbox_pending_oldest_age_seconds` · `outbox_failed`)를 갱신 |
| SSE 하트비트 | notification | 30초 | – | – | 연결 유지 ping |

## 주의

스케줄러를 여러 파드에서 돌릴 때와 「종류」 칸의 뜻입니다.

| 규칙 | 내용 |
|---|---|
| 여러 파드 | market은 파드가 2개라 스케줄러도 두 번 돈다 (Gateway는 스케줄러 없음). DB 행을 처리하는 작업은 `FOR UPDATE SKIP LOCKED`로 나눠 가져가고, 행이 아닌 한 번만 할 일은 ShedLock으로 한 인스턴스만 실행 |
| 끝까지 | 멱등이라 계속 보내도 안전 → 포기하지 않음 (택배 예약 · 취소, 정산 지급). 알림은 건마다가 아니라 "오래 남은 건이 있다"는 상태 기준으로 한 번 (예: 10분 넘게 `READY`). 상품 판매 완료 · 예약 해제는 이벤트라 스케줄러 없이 Outbox 재전송 · 소비 재시도 · DLT를 따름 |
| 보관 기한까지 재시도 | 토큰 폐기 기록을 보관 기한까지 재시도하고, 기한이 지나면 `EXPIRED`로 처리함 |
| 확인 후 포기 | 정한 횟수 뒤 `REVIEW_REQUIRED` (Saga 복구 5회, 토스 조회 실패 3회) · Outbox는 5회 뒤 `FAILED` + 팀 알림 · 검수 엔진 오류는 3회 뒤 관리자 확인 |
| – | 매 주기 그때 대상인 행만 처리하고, 재시도 개념이 없음 |
| ai-service | 파드가 늘었다 줄어 스케줄러를 두지 않음 |
