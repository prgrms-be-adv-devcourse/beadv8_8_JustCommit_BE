# 0035. Grafana를 replant.kro.kr/grafana로 공개한다

> 상태: ✅ 확정 · 결정일 2026-11-27 · 결정한 사람: 담당자(인프라)
> 대체: - · 대체됨: -

## 배경

Grafana는 데이터 서버 `127.0.0.1:3000`에만 열고 SSH 터널로만 접속했다. [ADR 0034](0034-grafana-ai-summary-claude-code-mcp.md)로 팀원이 Grafana에서 AI 요약을 보게 되면서, 보기만 하려는 팀원도 서버 키와 자기 IP의 SSH 허용이 필요해졌다. IP는 집 · 카페 · 핫스팟마다 바뀌고, 서버 키를 모두에게 나누면 Grafana만 보면 되는 사람에게 서버 전체 권한을 주게 된다.

## 결정

앱 서버 Nginx가 `https://replant.kro.kr/grafana/`를 데이터 서버 Grafana(사설 IP `10.0.7.79:3000`)로 넘긴다. 데이터 서버 3000은 앱 서버 보안 그룹에서만 받고, `127.0.0.1:3000` SSH 터널은 운영자 비상용으로 남긴다. 공개 전에 Grafana를 보안 패치가 이어지는 12.4.x로 올린다.

## 고른 이유

- 새 도메인 · 인증서 없이 기존 Nginx와 Let's Encrypt 인증서를 쓴다.
- 데이터 서버는 인터넷 공개 포트가 없는 그대로다 (Nginx만 3000에 닿음).
- 팀원은 브라우저 로그인만 하면 되고 서버 키 · SSH가 필요 없다. Claude Code도 터널 없이 `GRAFANA_URL=https://replant.kro.kr/grafana`로 붙는다.
- 보호: Grafana 개인 계정(Viewer) · 익명 차단 · 가입 차단 · HTTPS 전용 쿠키, Nginx 로그인 요청 수 제한(IP마다 분당 10회), Grafana 자체 로그인 잠금.
- 12.0 계열은 2026-02(12.0.10) 이후 패치가 없어 인터넷에 둘 수 없다. 12.4.15는 로컬에서 기존 대시보드 · 알림 16개 · 데이터 소스가 그대로이고 메모리도 약 123MiB였다 (13은 플러그인 프로세스가 늘어 메모리가 크게 늚 → #668).

## 다른 안

| 안 | 버린 이유 |
|---|---|
| 지금처럼 SSH 터널 + 팀원 IP | IP가 자주 바뀌어 보안 그룹을 계속 고쳐야 하고, 보기만 할 사람에게 서버 키가 퍼짐 |
| 새 하위 도메인(예: grafana.replant.kro.kr) | DNS · 인증서를 하나 더 관리해야 함. 하위 경로로 충분 |
| 데이터 서버에 Nginx를 두고 443 공개 | 데이터 서버(DB · Kafka)에 인터넷 공개 포트가 생김 |
| AWS Session Manager 포트 포워딩 | 허용 서비스 밖이라 운영진 문의가 필요하고, 팀원마다 IAM · 에이전트 설정이 필요 |

## 영향

- 서비스 계정 `claude-mcp` 토큰이 SSH 없이도 쓰이게 된다. Editor 권한이라 유출되면 Grafana API로 대시보드 · 알림 규칙까지 바꿀 수 있으므로, 사람마다 발급하고 유출이 의심되면 바로 지운다. ADR 0034의 「대시보드 · 알림을 바꿀 수 없다」는 Claude Code(MCP) 경로에 한한 말이다.
- [관측](../infra/observability.md) 「구성」 · 「Claude Code로 로그 조회 · 요약」, [포트](../reference/ports.md) grafana 행 · 보안 그룹
- `docker-compose.observability.yml` (12.4.15, 사설 IP 바인딩, 하위 경로 · 쿠키 설정), `deploy/nginx/replant.conf` (`/grafana/`), `.mcp.json` (기본 `GRAFANA_URL`)
- AWS 보안 그룹 `team06-replant-data-sg`: 앱 서버 보안 그룹에서 오는 3000 추가
