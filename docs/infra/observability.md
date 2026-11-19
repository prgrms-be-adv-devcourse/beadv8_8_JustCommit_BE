# 관측 (로그 · 지표 · 알림)

> 언제 읽나: 로그 형식, 추적, 대시보드, 알림을 다룰 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-19 · 관련: Miro 16, 노션 「정책 정리」 10.10, ADR 0008 · 0027

## 구성

앱 서버의 Alloy가 컨테이너 · 파드 로그를 모아 Loki로, 서비스가 OTLP로 보낸 추적을 받아 Tempo로 넘깁니다. 앱 서버 지표(`/actuator/prometheus` · node-exporter)도 Alloy가 긁어 Prometheus로 밀어 넣고(remote_write), 데이터 서버의 exporter는 Prometheus가 직접 읽습니다. 앱 서버에는 들어오는 포트를 더 열지 않습니다. Grafana가 대시보드와 알림을 맡아 Slack 웹훅으로 보냅니다. Grafana에서 Loki 로그와 Tempo 추적을 traceId로 서로 오갑니다. 로그용 OpenSearch · ELK는 쓰지 않습니다. Grafana는 포트를 외부에 열지 않고 SSH 터널로만 접속합니다.

| 도구 | 하는 일 | 보관 |
|---|---|---|
| Alloy | 로그 · 지표 · 추적 수집 (앱 서버) | – |
| Loki | 로그 | 14일 |
| Tempo | 분산 추적 (샘플링 100%) | 3일 |
| Prometheus | 지표 | 7일 |
| Grafana | 화면 · 알림 | – |

지표를 스스로 내보내지 못하는 서버 · DB에는 옆에 exporter를 붙여 Prometheus가 읽게 합니다.

| 수집 대상 | 방법 |
|---|---|
| 서비스 | `/actuator/prometheus` (HTTP · Hikari · Tomcat · JVM) |
| 서버 (앱 · 데이터) | node-exporter (CPU · 메모리 · 디스크 · CPU steal). 앱 서버는 Alloy 내장 `prometheus.exporter.unix`로 같은 지표 |
| PostgreSQL | postgres-exporter |
| Kafka | kafka-exporter (토픽별 offset → DLT 유입 알림) |
| 컨테이너 · 파드 메모리 (앱 서버) | Alloy 내장 cAdvisor (사용량 · 한도) |
| Redis | ⬜ 미정 |

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
| 서비스 다운 | `/actuator/prometheus` 응답 없음 (`up == 0`), 수집 데이터가 아예 없어도 (Alloy 멈춤) | 1분 |
| 5xx 급증 | 5xx 비율 5% 이상 (5분 동안 요청이 30건 넘을 때만, 요청이 적을 때 1건 실패로 울리지 않게) | 5분 |
| Outbox 대기 | 가장 오래된 PENDING이 1분 초과 · `FAILED` 발생 (`max by (service) (outbox_pending_oldest_age_seconds) > 60`, `max by (service) (outbox_failed) > 0`) | 즉시 |
| DLT 유입 | `*.DLT` 토픽에 1건이라도 (kafka-exporter offset 증가) | 즉시 |
| 디스크 | 80% 이상 | 5분 |
| 메모리 | 앱 서버: 컨테이너 · 파드 한도의 90% 이상, 데이터 서버: 서버 메모리 90% 이상 | 5분 |
| 배송 예약 · 취소 지연 | `replant_shipment_stuck{status="READY"}`(만든 지 10분 넘게 택배 예약 안 됨) 또는 `{status="CANCEL_PENDING"}`(취소를 누른 지 10분 넘게 택배사 결과 없음) 1 이상. market이 수집 때마다 DB에서 셈, 원인은 배송 `last_error` | 상태 기준 1번, 반복 1시간 |
| 정산 지급 지연 | 10분 넘게 미지급(`PENDING`, `FAILED`)인 정산 1건 이상 (`max(settlement_payouts_delayed) > 0`) | 상태 기준 1번, 반복 1시간 |
| `REVIEW_REQUIRED` | 결제 확인 필요 주문 1건 이상 (`max(market_orders_review_required) > 0`) → [처리 순서](payment-review-runbook.md) | 상태 기준 1번, 반복 1시간 |

규칙 · 보내는 곳 · 반복 간격은 파일로 등록합니다: `deploy/observability/grafana/provisioning/alerting/` (`rules.yml` · `contact-points.yml` · `policies.yml`). 같은 알림 · 같은 서비스는 한 메시지로 묶고, 해소되지 않으면 1시간마다 다시 보냅니다.

## 부하 테스트

k6로 측정합니다.

| 시나리오 | 목표 |
|---|---|
| 상품 조회 100명 3분 | p95 0.5초 · 실패 1% 미만 |
| 주문 → 결제 20명 5분 | p95 1초 |
| 같은 상품 50명 동시 구매 | 정확히 1명 성공 |
| 순간 0 → 200명 30초 | 5xx 없이 요청 수 제한 작동 (서킷 브레이커는 쓰지 않음) |
