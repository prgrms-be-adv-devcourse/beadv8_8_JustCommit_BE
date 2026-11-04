# 스케줄러

> 언제 읽나: @Scheduled 작업의 주기 · 역할을 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro A4

## 작업 표

| 작업 | 서비스 | 주기 | 하는 일 |
|---|---|---|---|
| 결제 대기 만료 | market | 30초 | 10분 지난 결제 대기 주문 만료 · 예약 해제 |
| 자동 구매 확정 | market | 1시간 | 배송 완료 후 72시간 지난 상품 확정 (환불 요청 중이면 건너뜀) |
| 배송 재접수 | market | 5분 | `READY` 배송을 택배사에 다시 접수 |
| 택배 취소 재시도 | market | 5분 | 실패한 택배 취소 다시 시도 |
| SSE 하트비트 | notification | 30초 | 연결 유지 ping |
| Saga 복구 | market | 🟨 1분 | 5분 넘게 멈춘 주문(`PAYMENT_PENDING` 등)을 같은 멱등 키로 확인, 5회 실패하면 `REVIEW_REQUIRED` |
| 결과 불명 결제 조회 | payment | ⬜ | 토스 결과 불명 건 조회 3회 → `REVIEW_REQUIRED` |
| Outbox 재전송 | member · product · market · settlement | ⬜ | 발행 안 된 이벤트 다시 보내기 |

## 주의

- 세미에서는 `@Scheduled` 작업이 기본 스레드 1개를 함께 썼습니다. market은 4개 이상이라 스레드 풀 크기를 설정합니다 (🟨).
- 서비스마다 컨테이너가 1개라 중복 실행 잠금(ShedLock)은 지금 필요 없습니다.
- ai-service는 2개까지 늘어나므로 스케줄러를 두지 않습니다.
