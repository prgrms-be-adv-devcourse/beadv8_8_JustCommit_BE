# 관측 (로그 · 지표 · 알림)

> 언제 읽나: 로그 형식, 추적, 대시보드, 알림을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-11 · 관련: Miro 16, 노션 「정책 정리」 10.10, ADR 0008 · 0019

## 구성

Alloy가 컨테이너 로그를 모아 Loki로, 서비스가 OTLP로 보낸 추적을 받아 Tempo로 넘깁니다. Prometheus가 `/actuator/prometheus`를 수집하고, Grafana가 대시보드와 알림을 맡아 Slack 웹훅으로 보냅니다. Grafana에서 Loki 로그와 Tempo 추적을 traceId로 서로 오갑니다. 로그용 OpenSearch · ELK는 쓰지 않습니다. Grafana는 포트를 외부에 열지 않고 SSH 터널로만 접속합니다.

| 도구 | 하는 일 | 보관 |
|---|---|---|
| Alloy | 로그 · 추적 수집 | – |
| Loki | 로그 | 14일 |
| Tempo | 분산 추적 (샘플링 100%) | 3일 |
| Prometheus | 지표 | 7일 |
| Grafana | 화면 · 알림 | – |

지표를 스스로 내보내지 못하는 서버 · DB에는 옆에 exporter를 붙여 Prometheus가 읽게 합니다.

| 수집 대상 | 방법 |
|---|---|
| 서비스 | `/actuator/prometheus` (HTTP · Hikari · Tomcat · JVM) |
| 서버 (앱 · 데이터) | node-exporter (CPU · 메모리 · 디스크 · CPU steal) |
| PostgreSQL | postgres-exporter |
| Kafka · Redis | ⬜ 미정 |

대시보드 「서버 CPU」 패널에 CPU steal을 같이 표시합니다. 측정 중 steal이 오르면 t3 크레딧이 바닥나 AWS가 CPU를 묶은 것이므로 그 측정은 무효로 봅니다.

## 로그

| 항목 | 내용 |
|---|---|
| 형식 | 운영 JSON(ECS, Spring Boot 내장 structured logging), 로컬 텍스트 |
| 추적 | Micrometer Tracing(OTel 브리지) traceId를 REST · Kafka 헤더 `traceparent`로 전파해 로그에 기록, span은 OTLP로 Alloy → Tempo |
| MDC | 인증 필터(`UserIdMdcFilter`)가 memberId를 넣고, `common-logging`은 형식 · 키 이름만 정함 |
| 메시지 | 한국어 한 문장 + 값은 key-value (`log.atInfo().addKeyValue(...)`), 객체 통째 출력 금지 |
| 금지 | 이름 · 전화 · 주소 · 이메일 · 계좌 · 비밀번호 · 토큰 · 결제 키 · 쿼리스트링을 로그에 남기지 않음 |
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
| 순간 0 → 200명 30초 | 5xx 없이 요청 수 제한 작동 (서킷 브레이커는 쓰지 않음) |
