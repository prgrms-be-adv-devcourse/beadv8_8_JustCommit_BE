# 0019. 분산 추적은 Tempo로 추가

> 상태: ✅ 확정 · 결정일 2026-11-10 · 결정한 사람: 인프라 담당
> 대체: 0008 · 대체됨: -

## 배경

[ADR 0008](0008-observability-loki-prometheus.md)은 서버 메모리를 아끼려고 Tempo · Zipkin을 빼고, traceId를 로그에만 남기기로 했습니다. 그런데 부하 테스트에서 "요청 하나가 어느 구간(Gateway · 서비스 · 서비스 간 호출 · DB · 외부 API)에서 느려지는지"를 보여 줘야 하고, 로그의 traceId만으로는 구간별 시간을 한눈에 볼 수 없습니다.

## 결정

0008의 구성(Alloy · Loki · Prometheus · Grafana)은 그대로 두고 Tempo를 더합니다. 서비스는 Micrometer Tracing(OTel 브리지)으로 추적을 OTLP 형식으로 Alloy에 보내고, Alloy가 Tempo로 넘깁니다. Grafana에서 Loki 로그와 Tempo 추적을 traceId로 서로 오갑니다. 샘플링은 100%(트래픽이 적음), 보관은 3일입니다.

## 고른 이유

이미 쓰는 Alloy · Loki · Prometheus · Grafana와 같은 회사 제품이라 수집기를 새로 들이지 않고, Grafana 한 화면에서 지표 → 추적 → 로그로 이어 볼 수 있습니다. 검색 인덱스를 거의 만들지 않고 디스크에 쌓아 가볍습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| 0008 그대로 (로그 traceId만) | 구간별 지연을 보여 줄 수 없음 |
| Jaeger | 제대로 쓰려면 Elasticsearch · OpenSearch 저장소가 필요. 상품 검색용 OpenSearch에 넣으면 검색 성능 측정이 흐려짐. Loki 연결도 추가 설정 |
| Zipkin | 기능이 적고 Grafana · Loki 연결이 약함 |

## 영향

[관측](../infra/observability.md), [공통 모듈](../conventions/common-modules.md) `common-logging`, [개요](../overview.md). 구현은 #301.
