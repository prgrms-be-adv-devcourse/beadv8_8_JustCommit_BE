# CHANGELOG 조각 파일

> 언제 읽나: 문서를 고친 PR에서 변경 기록을 남길 때, Sprint 종료에 변경 기록을 통합할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-24 · 관련: [문서 변경 기록](../CHANGELOG.md), `scripts/changelog.py`, #640

변경 기록은 `docs/CHANGELOG.md`에 직접 쓰지 않고 이 폴더에 이슈별 조각 파일로 씁니다. 여러 PR이 `CHANGELOG.md`의 같은 자리를 고쳐 머지 충돌이 나던 것을 없애기 위한 방식입니다. 조각 파일은 Sprint 종료 때 스크립트로 `CHANGELOG.md`에 통합합니다.

이 README는 지우지 않습니다. 조각 파일 방식으로 전환했다는 기준이라 CI가 있는지 확인합니다. 내용은 고쳐도 됩니다.

## 조각 파일 쓰기

| 항목 | 규칙 |
|---|---|
| 파일 | `docs/changelog.d/<이슈번호>.md` (예: `546.md`) |
| 같은 이슈로 PR을 더 올릴 때 | `<이슈번호>-<식별자>.md`. 식별자는 영문 소문자 · 숫자 · 하이픈 (예: `546-docs.md`) |
| 내용 | 한 줄이 항목 하나: `- YYYY-MM-DD · 문서 · 바뀐 내용 (#이슈번호)` |
| 날짜 | 문서를 고친 날 |
| 줄 끝 번호 | PR 번호가 아니라 이슈 번호. 파일명의 번호와 같아야 함 |
| 여러 줄 | 쓸 수 있음. 빈 줄은 무시하고, 제목이나 설명처럼 항목이 아닌 줄은 쓰지 않음 |
| 빈 파일 | 둘 수 없음 |
| 이미 `develop`에 있는 조각 파일 | 고치거나 지우지 않음. 추가로 남길 내용은 식별자를 붙인 새 파일에 씀 |
| `docs/CHANGELOG.md` | 직접 고치지 않음. 통합 스크립트의 결과만 들어감 |

```
- 2026-11-24 · conventions/auth.md · decisions/0032 · 폐기 이후 Access 발급 조건 추가 (#546)
```

PR을 올리기 전에 형식을 확인합니다.

```bash
python3 scripts/changelog.py check
```

## 최근 변경 확인하기

`docs/CHANGELOG.md`에는 마지막 통합까지의 기록만 있습니다. 그 뒤의 변경은 이 폴더의 조각 파일에 있으므로, 작업을 시작할 때 둘을 함께 봅니다.

## 열려 있던 PR 전환하기

조각 파일 방식으로 바뀌기 전에 `docs/CHANGELOG.md`를 직접 고친 PR은 아래 순서로 옮깁니다.

1. `develop`을 반영하기 전에, 내 PR이 `docs/CHANGELOG.md`에 직접 추가한 줄을 따로 보관합니다 (`git diff origin/develop...HEAD -- docs/CHANGELOG.md`에서 `+`로 시작하는 줄). 충돌을 푸는 과정에서 기록이 빠지지 않게 하기 위해서입니다
2. 전환 PR(#640)이 머지된 뒤의 최신 `develop`을 내 브랜치에 반영합니다. `docs/CHANGELOG.md`에서 충돌이 나면 `develop` 쪽 내용을 택합니다
3. 보관해 둔 내 줄만 `docs/changelog.d/<이슈번호>.md`에 씁니다. 줄 끝 번호는 이슈 번호로 맞춥니다
4. `docs/CHANGELOG.md`가 `develop`과 같은지 확인하고, 다르면 되돌립니다 (`git checkout origin/develop -- docs/CHANGELOG.md`). 다른 팀원의 기록은 `develop`에 있는 그대로 남습니다
5. `python3 scripts/changelog.py check`와 `python3 scripts/changelog.py verify-pr origin/develop`이 통과하는지 확인합니다

## Sprint 종료 통합

담당자 한 명이 Sprint마다 한 번 진행합니다. 통합 대상은 이미 `develop`에 머지된 조각 파일입니다. 조각 파일이 하나도 없으면 통합 이슈와 PR을 만들지 않습니다.

1. CHANGELOG 통합 Task 이슈를 만듭니다 (예: `[common] Sprint 4 CHANGELOG 통합`)
2. 최신 `develop`에서 브랜치를 만듭니다 (`{GitHub ID}/common-{이슈 번호}-changelog-merge`)
3. `python3 scripts/changelog.py merge`
4. 결과를 확인합니다. `git status --short`에 `docs/CHANGELOG.md` 수정과 조각 파일 삭제만 있어야 하고, `python3 scripts/changelog.py verify-pr origin/develop`이 통과해야 합니다. 결과를 손으로 고치지 않습니다 (CI가 스크립트 결과와 글자까지 비교)
5. `CHANGELOG.md` 변경과 조각 파일 삭제를 같은 PR로 올리고, 본문에 `Closes #이슈번호`를 넣습니다. 통합 작업 자체의 조각 파일은 만들지 않습니다
6. 통합 PR을 올린 뒤 `develop`에 새 조각 파일이 들어와도 그대로 둡니다. 다음 통합 때 들어갑니다

| 스크립트가 하는 일 | 내용 |
|---|---|
| 넣는 자리 | `## 기록` 바로 아래. 기존 기록은 순서와 글자를 바꾸지 않음 |
| 정렬 | 날짜 내림차순 → 같은 날짜는 이슈 번호 내림차순 → 파일명 → 파일 안 줄 순서 |
| 중복 | 이미 기록에 있는 줄, 조각 파일끼리 같은 줄은 한 번만 넣음 |
| 마지막 수정 | 기존 날짜와 새 항목의 가장 늦은 날짜 중 더 늦은 쪽 |
| 조각 파일 | 통합한 파일을 삭제. 이 README는 남김 |
| 형식 오류 | 오류를 모두 출력하고 `CHANGELOG.md`와 조각 파일을 그대로 둠 |
| 중간에 멈춤 | 다시 실행. 기록을 먼저 쓰고 조각 파일을 나중에 지우므로 빠지거나 두 번 들어가지 않음 |
| 조각 파일 없음 | 아무것도 바꾸지 않고 정상 종료 |

## 실행 조건

| 항목 | 조건 |
|---|---|
| 실행 위치 | 저장소 루트 |
| Python | 3.7 이상. 표준 라이브러리만 사용 |
| Git | `verify-pr`과 테스트에 필요. 테스트는 2.28 이상 (`git init -b`) |
| 테스트 | `python3 -m unittest discover -s scripts -p "test_*.py"` |

## CI가 확인하는 것

PR CI의 `build` job이 두 단계를 실행합니다.

| 단계 | 실행되는 PR | 내용 |
|---|---|---|
| CHANGELOG 스크립트 테스트 | 모든 PR (`develop` · `main` 대상) | `scripts/test_changelog.py` |
| CHANGELOG 규칙 확인 | `develop` 대상 PR (문서만 바뀐 PR 포함) | `python3 scripts/changelog.py verify-pr` |

| 규칙 확인 | 실패하는 경우 |
|---|---|
| 조각 파일 형식 | 「조각 파일 쓰기」의 파일명 · 내용 규칙에 어긋남 |
| README | 이 파일이 없음 |
| 기존 조각 파일 | `develop`에 있던 조각 파일의 내용을 고침 |
| `docs/CHANGELOG.md` | "PR이 지운 조각 파일을 통합한 결과"와 다름. 일반 PR이 직접 고친 경우(안내 문구 · 머리말 포함), 통합 PR이 스크립트 결과를 손으로 고친 경우, 조각 파일을 통합하지 않고 지운 경우 |

CI는 조각 파일의 형식과 기존 기록의 보존만 확인합니다. 문서를 고친 PR이 조각 파일을 썼는지는 CI가 확인하지 않습니다. 작성 여부는 [AGENTS.md](../../AGENTS.md) 「문서와 코드를 맞추는 규칙」과 리뷰(AI 리뷰 포함)에서 봅니다.

`main` 대상 PR에서는 규칙 확인을 하지 않습니다. 지금은 `develop` 중심으로 작업하고 변경 기록도 `develop`으로 들어올 때 확인하기 때문입니다. 최종 `main` 반영 절차는 아직 정해지지 않았고, 정해지면 이 범위를 다시 검토합니다.

같은 확인을 로컬에서 하려면 `python3 scripts/changelog.py verify-pr origin/develop`을 실행합니다.
