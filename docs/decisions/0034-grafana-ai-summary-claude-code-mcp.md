# 0034. Grafana AI 요약은 Claude Code + Grafana MCP로 만든다

> 상태: ✅ 확정 · 결정일 2026-11-27 · 결정한 사람: 담당자(인프라)
> 대체: - · 대체됨: -

## 배경

Sprint 4 장애 감지 · 대응(#667)에서 알림이 「원인 후보」까지 알려 주게 하려 했다(#668). 처음 범위는 Grafana 알림이 오면 AI가 로그 · 지표를 요약해 Slack 메시지에 붙이는 것이었다. 진행 중에 팀원이 함께 보는 Grafana 안에서 요약을 보고 싶다는 요구, 로그 검색이 불편하다는 요구, Claude API 사용료가 부담이라는 조건이 더해졌다.

## 결정

Claude Code에 Grafana 공식 MCP 서버(`grafana/mcp-grafana`)를 연결하고, `/grafana-summary` 스킬로 만든 요약 · 원인 후보를 Grafana 주석(태그 `ai-summary`)으로 남긴다. 팀원은 대시보드 「AI 요약」 패널에서 본다. Grafana는 12.0.0을 유지하고 플러그인을 추가하지 않는다.

## 고른 이유

- Claude 구독(Claude Code) 안에서 동작해 Claude API 키 · 별도 결제가 없다.
- 결과를 Grafana 안에서 본다는 요구를 주석 + 주석 목록 패널로 채운다.
- Grafana 버전 업그레이드 · 외부 플러그인 · 새 컨테이너가 필요 없어 운영 변경이 서비스 계정 하나와 대시보드 파일뿐이다.
- MCP 도구 범위를 조회 + 주석으로 좁혀, Claude Code 경로로는 대시보드 · 알림을 바꿀 수 없다. 서비스 계정은 Editor라(Viewer는 대시보드 편집 권한을 줘도 주석을 못 씀) 토큰이 새면 Grafana API로는 바꿀 수 있으므로, 토큰은 사람마다 발급하고 유출 의심 시 바로 지운다.
- 한계: 요약은 사람이 Claude Code에서 요청할 때만 만들어진다(자동 아님). 팀원이 Grafana 화면에서 직접 AI에게 묻지는 못한다(대신 Logs Drilldown으로 조회식 없이 로그를 좁혀 본다).

## 다른 안

| 안 | 버린 이유 |
|---|---|
| 알림 웹훅 → 요약 프로그램(Claude API) → Slack | 결과를 Grafana가 아닌 Slack에서 봄. API 사용료가 듦 |
| Grafana LLM 플러그인(`grafana-llm-app`) + Ask O11y | Grafana 안 채팅이 되지만 Grafana 13 이상이 필요하고(13은 데이터 소스 플러그인마다 프로세스가 떠 메모리가 크게 늚), 외부 회사 0.x 플러그인이며, API 사용료가 듦 |
| Grafana Assistant | Grafana Cloud 전용이라 자체 설치 Grafana에서 쓸 수 없음 |
| 요약 프로그램이 주기적으로 주석을 남김(Claude API) | 자동이지만 API 사용료가 듦. 필요해지면 같은 주석 형식으로 추가할 수 있음 |

## 영향

- [관측](../infra/observability.md) 「Claude Code로 로그 조회 · 요약」
- `.mcp.json`, `.claude/skills/grafana-summary/SKILL.md`, `deploy/observability/grafana/dashboards/replant-msa.json` (「AI 요약」 패널 · 주석 표시)
- 운영 Grafana 서비스 계정 `claude-mcp`
- [용어집](../glossary.md) 「AI 요약」
