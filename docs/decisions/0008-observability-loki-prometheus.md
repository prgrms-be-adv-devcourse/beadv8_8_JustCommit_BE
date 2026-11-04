# 0008. 관측은 Alloy · Loki · Prometheus · Grafana

> 상태: ✅ 확정 · 결정일 2026-11-02 · 결정한 사람: 팀 회의
> 대체: - · 대체됨: -

## 배경

서비스가 여러 개가 되면 로그와 지표를 한곳에서 봐야 합니다. 데이터 서버 메모리는 넉넉하지 않습니다.

## 결정

Alloy → Loki(14일), Prometheus(7일), Grafana 대시보드 · 알림 → Slack. 운영 로그는 JSON(ECS), traceId는 REST · Kafka 헤더로 전파해 로그에만 남깁니다.

## 고른 이유

Loki는 OpenSearch(ELK)보다 메모리를 적게 쓰고, traceId만으로도 요청 하나를 여러 서비스 로그에서 이어 볼 수 있습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| ELK · 로그용 OpenSearch | 메모리 |
| Tempo · Zipkin | 서버 추가, 지금은 로그 traceId로 충분 |

## 영향

[관측](../infra/observability.md)
