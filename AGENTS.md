# AGENTS.md

replant(팀 JustCommit)는 직접 키운 식물을 사고파는 C2C 식물 마켓입니다. 세미 프로젝트의 Spring Modulith 모놀리스(`src/`)를 최종 프로젝트에서 서비스 8개(gateway · member · product · market · payment · settlement · notification · ai)로 나누는 중입니다. 설계는 `docs/`에 있습니다.

## 문서의 원본

- 설계 · 정책 · 값의 원본은 이 레포의 `docs/`입니다. 노션 기획안은 제출용 요약, Miro는 그림입니다.
- 노션 · Miro와 `docs/`가 다르면 `docs/`를 따르고, 다른 곳을 사용자에게 알립니다.
- `docs/legacy/`는 세미 프로젝트(모놀리스) 문서입니다. 참고만 하고, 현재 설계와 다르면 `docs/`의 다른 문서를 따릅니다.

## 작업을 시작하기 전

1. `docs/CHANGELOG.md`의 최근 항목과 `git log --oneline -15 -- docs/`로 최근에 바뀐 설계를 확인합니다.
2. 아래 문서 지도에서 작업에 맞는 문서를 읽습니다. 문서 머리말의 「상태」가 `⬜ 작성 전`이면 그 내용은 아직 정해지지 않았으니 사용자에게 묻습니다.

## 문서 지도

| 할 일 | 먼저 읽을 문서 |
|---|---|
| 특정 서비스 코드 수정 | `docs/services/{서비스}.md` → 그 문서가 링크한 conventions |
| 외부 API 추가 · 수정 | `docs/conventions/api-style.md`, `docs/reference/external-apis.md` |
| 토큰 · 권한 · 로그아웃 | `docs/conventions/auth.md`, `docs/reference/redis-keys.md` |
| 서비스 간 REST 호출 추가 | `docs/conventions/internal-api.md`, `docs/reference/internal-apis.md`, `docs/conventions/resilience.md` |
| Kafka 이벤트 발행 · 구독 | `docs/conventions/events.md`, `docs/reference/kafka-topics.md` |
| 공통 모듈(common-*) 수정 | `docs/conventions/common-modules.md` |
| 주문 · 결제 · 취소 · 환불 · 정산 흐름 | `docs/flows/` 해당 문서 |
| 사진 검수 · 검색 · 추천 | `docs/flows/image-review.md`, `docs/flows/search-recommend.md`, `docs/services/ai.md` |
| 포트 · 환경변수 · Redis 키 · 스케줄러 | `docs/reference/` 해당 문서 |
| 배포 · 로그 · 알림 · AWS | `docs/infra/` 해당 문서 |
| DB · 테이블 추가 · Flyway 마이그레이션 | `docs/infra/database.md`, `docs/services/{서비스}.md` 「소유 테이블」 |
| 테스트 추가 | `docs/testing/strategy.md`, `docs/testing/scenarios.md` |
| 용어 뜻 | `docs/glossary.md` |
| "왜 이렇게 정했지?" | `docs/decisions/` |
| 아직 안 정한 것 | `docs/open-questions.md` |

## 문서와 코드를 맞추는 규칙

`docs/`에 적힌 정책 · 설계와 다르게 구현해야 하면 코드만 바꾸지 않습니다.

1. 구현 전에 사용자에게 어느 문서의 어떤 내용과 다른지, 왜 달라져야 하는지 알립니다.
2. 사용자가 동의하면 같은 PR에서 해당 문서를 고칩니다.
3. 결정이 바뀐 경우 `docs/decisions/`에 새 ADR을 추가하고, 이전 ADR의 머리말에 `대체됨: NNNN`만 적습니다.
4. `docs/CHANGELOG.md` 맨 위에 `날짜 · 문서 · 바뀐 내용 (#PR)` 한 줄을 추가합니다.
5. PR 본문의 「📄 문서 변경」 칸에 바꾼 문서를 적습니다. 문서를 안 바꿨으면 「문서 영향 없음」에 체크합니다.

작업 중에 코드와 문서가 이미 어긋난 곳을 발견하면, 어느 쪽이 맞는지 임의로 정하지 말고 사용자에게 알립니다.

## 문서 정리 규칙

문서의 틀은 유지하고 내용만 고칩니다.

- 바꾸지 않는 것: 폴더 구조, 파일 이름, 머리말 3줄의 형식, 서비스 카드(`docs/services/*.md`)의 `##` 목차와 순서, 다른 문서의 `##` 제목. 바꿔야 하면 내용 수정과 섞지 말고 따로 사용자에게 제안합니다.
- 머리말: 내용을 고치면 「마지막 수정」 날짜를 바꾸고, 「상태」를 `✅ 확정` / `🟨 제안` / `⬜ 미정` / `⬜ 작성 전` 중 하나로 맞춥니다.
- 한 내용은 한 곳에만 씁니다. 같은 내용이 필요하면 원본 문서로 링크합니다. 값(토픽 · 키 · 포트 · 타임아웃)의 원본은 `docs/reference/`와 `docs/conventions/resilience.md`입니다.
- 서비스 카드에는 API · 이벤트 · 스케줄러의 이름만 적고 상세는 `docs/reference/`에 씁니다.
- 용어는 `docs/glossary.md`의 「표준 용어」만 씁니다. 별칭은 검색용이라 본문에 쓰지 않습니다. 새 용어나 새 이름이 필요하면 glossary에 먼저 추가합니다.
- 설명 글보다 표를 씁니다. 클래스 · 테이블 · 토픽 · 경로 · 상태값 이름은 코드 글꼴(`` ` ``)로 정확히 씁니다.
- 상태 표시는 ✅ 확정 · 🟨 제안 · ⬜ 미정만 씁니다. ✏️ · 🆕 같은 변경 표시는 문서에 남기지 않고 `CHANGELOG.md`에 씁니다.
- `##` 하나에는 주제 하나만 둡니다. 파일이 300줄을 넘으면 나누기 전에 사용자에게 묻습니다.
- 새 문서가 필요하면 기존 폴더 중 맞는 곳에 같은 머리말 양식으로 만들고, 이 파일의 문서 지도에 한 줄 추가합니다.
- `docs/decisions/`의 ADR은 내용을 고치지 않습니다. 새 ADR은 `_template.md`를 복사해 다음 번호로 만듭니다.
- `docs/legacy/`는 고치지 않습니다.
- 비밀값(키 · 비밀번호 · 토큰 값)은 어떤 문서에도 적지 않습니다.

## 작업 규칙

- 브랜치: `{GitHub ID}/{도메인}-{이슈 번호}-{설명}` (예: `gasic37200/market-161-shipment-cancel`), 기본 브랜치는 `develop`
- 커밋 · PR 제목: `Feat|Fix|Refactor|Chore|Docs|Test: 한국어 설명 (#이슈)`
- PR 본문은 `.github/pull_request_template.md`를 따르고 `Closes #이슈`를 넣습니다.

## 명령어

```bash
./gradlew build          # 빌드 + 테스트
./gradlew test           # 테스트만
docker compose up -d     # 로컬 의존 서비스 (PostgreSQL · Redis · Kafka · RustFS)
```
