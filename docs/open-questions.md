# 미정 항목

> 언제 읽나: 아직 정하지 않은 것이 무엇이고 누가 정하는지 확인할 때
> 상태: ⬜ 미정 · 마지막 수정 2026-11-04 · 관련: Miro 18, 노션 기획안 맨 위 「남은 미정」

## 미정 목록

| 항목 | 지금 안 | 담당 | 기한 |
|---|---|---|---|
| Saga 결제 보상 목록 | 결제 흐름만 정리 | 성수 | Sprint 1 |
| 이벤트별 payload 필드 | – | 각 발행 서비스 (#227) | Sprint 1 |
| 내부 REST 🟨 제안 항목 | [내부 API 목록](reference/internal-apis.md)의 🟨 행 | 각 담당 | 서비스 분리 때 |
| 탈퇴 가능 여부 확인 API | member → payment · market · settlement 조회 (경로 없음) | member · 각 담당 | 탈퇴 기능 만들 때 |
| Resilience4j 버전 | Spring Boot 4 호환 버전 확인 | 공통 | Sprint 1 시작 |
| 메모리 실측 · 모니터링 위치 | 넘치면 앱 서버로 | 인프라 | Sprint 1 |
| DB 분리 순서 · Flyway 규칙 | – | rlaksl (#253) | Sprint 1 |
| JWT 키 교체 절차 | – | 인프라 | – |
| 회원별 Refresh Set 키 이름 | 🟨 `auth:refresh-set:{memberId}` | member | Sprint 1 |
| DLT 재처리 방법 | 🟨 `failed_event` 표 + 재처리 내부 API | 공통 (messaging) | Sprint 2 |
| `outbox_event` 칸 이름 · 타입 | [이벤트 규칙](conventions/events.md#outbox)의 표 | 공통 (messaging) | Sprint 1 |
| Rekognition 사용 승인 · EC2 역할 | aws 채널 문의 | 인프라 | Sprint 2 전 |
| `ProductApproved` 추천용 필드 | – | 성수 | Sprint 2 |
| OpenSearch `nori` · 인덱스 매핑 | – | 검색 담당 | Sprint 1 |
| 상담용 LLM 모델 · 챗봇 경로 | – | AI 담당 | Sprint 2 |
| ai-service NodePort · 벡터 테이블 이름 | – | 인프라 · AI 담당 | Sprint 2 |
| API 경로 개선 시점 | 서비스 분리 때 프론트와 함께 | 팀 | – |
| 확장 화면 범위 | 판매자 상점 · 리포트 · 마이페이지 등 | 팀 | – |
| Grafana 접속 제한 방법 | – | 인프라 | – |

정해지면 이 표에서 행을 지우고, 결정이면 `decisions/`에 ADR을 추가하고, 해당 문서를 고칩니다.
