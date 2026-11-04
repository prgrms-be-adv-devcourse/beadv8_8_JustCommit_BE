# 0007. 서비스 간 호출은 RestClient + @HttpExchange, 디스커버리 없음

> 상태: ✅ 확정 · 결정일 2026-11-02 · 결정한 사람: 팀 회의
> 대체: - · 대체됨: -

## 배경

서비스 간 REST 클라이언트와 주소 찾는 방식을 정해야 했습니다.

## 결정

`RestClient` + HTTP Interface(`@HttpExchange`)를 쓰고, 주소는 Docker 서비스 이름과 사설 IP 환경변수로 정합니다. 내부 API는 `/internal/v1` + `X-Internal-Key`, 멱등 키는 업무 ID입니다.

## 고른 이유

Spring 기본 기능이라 의존성이 적고, 서버 2대 · 컨테이너 1개씩이라 디스커버리가 필요 없습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| OpenFeign | 추가 의존성, Spring Cloud 버전 맞추기 |
| Eureka 등 디스커버리 | 인스턴스가 고정이라 불필요 |

## 영향

[서비스 간 REST 규칙](../conventions/internal-api.md)
