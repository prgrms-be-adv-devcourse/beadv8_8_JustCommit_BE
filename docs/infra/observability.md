# 관측 (로그 · 지표 · 알림)

> 언제 읽나: 로그 형식, 대시보드, 알림을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-06 · 관련: Miro 16

## 구성

Alloy가 컨테이너 로그를 모아 Loki로, Prometheus가 `/actuator/prometheus`를 수집하고, Grafana가 대시보드와 알림을 맡아 Slack 웹훅으로 보냅니다. 로그용 OpenSearch · ELK는 쓰지 않습니다.

| 도구 | 보관 |
|---|---|
| Loki | 14일 |
| Prometheus | 7일 |

## 로그

| 항목 | 내용 |
|---|---|
| 형식 | 운영 JSON(ECS, Spring Boot 내장 structured logging), 로컬 텍스트 |
| 추적 | Micrometer Tracing traceId를 REST · Kafka 헤더 `traceparent`로 전파, 로그에만 기록 (Tempo · Zipkin 없음) |
| MDC | `UserIdMdcFilter`로 memberId 기록 |
| 금지 | 이름 · 전화 · 주소 · 계좌 · 토큰을 로그에 남기지 않음 |
| 디스크 | Docker 로그 `max-size: 20m`, `max-file: 5` |
| 재시도 로그 | 몇 번째 재시도인지 한 줄씩, 최종 실패는 ERROR 1줄 |
| 정상 상황 | SSE 끊김(`AsyncRequestNotUsableException`) 등은 DEBUG |

## 알림 규칙

| 알림 | 기준 | 지속 |
|---|---|---|
| 서비스 다운 | health 응답 없음 | 1분 |
| 5xx 급증 | 5xx 비율 5% 이상 | 5분 |
| Outbox 대기 | 가장 오래된 PENDING이 1분 초과 · `FAILED` 발생 | 즉시 |
| DLT 유입 | 1건이라도 | 즉시 |
| 디스크 | 80% 이상 | 5분 |
| 메모리 | 컨테이너 한도의 90% 이상 | 5분 |
| 배송 예약 지연 | 10분 넘게 `READY`인 배송 1건 이상 | 상태 기준 1번, 반복 1시간 |
| `REVIEW_REQUIRED` | 발생 | 상태 기준 1번, 반복 1시간 |

## 부하 테스트

k6로 측정합니다.

| 시나리오 | 목표 |
|---|---|
| 상품 조회 100명 3분 | p95 0.5초 · 실패 1% 미만 |
| 주문 → 결제 20명 5분 | p95 1초 |
| 같은 상품 50명 동시 구매 | 정확히 1명 성공 |
| 순간 0 → 200명 30초 | 5xx 없이 서킷 · 요청 제한 작동 |
