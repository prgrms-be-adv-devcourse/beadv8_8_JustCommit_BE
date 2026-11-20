# 카카오 연결 해제 웹훅 멈춘 기록 처리

> 언제 읽나: 「카카오 연결 해제 웹훅 처리 멈춤」 알림을 받았을 때 (member 담당)
> 상태: ✅ 확정 · 마지막 수정 2026-11-19 · 관련: [흐름 · 운영 알림과 대응](../flows/signup-and-replica.md#운영-알림과-대응), [관측](observability.md)

## 언제 생기나

처리 단계마다 최초 포함 5번 실패하면 수신 기록이 `FAILED`가 되고, 멈춘 단계가 `resume_state`에 남습니다. 스케줄러는 `FAILED`를 다시 집지 않습니다.

| 멈춘 단계(`resume_state`) | 주로 생기는 원인(`last_error`) |
|---|---|
| `PENDING` | 카카오 연결 조회 실패 `KAKAO_UNAVAILABLE` |
| `INACTIVATED` | 탈퇴 조건 확인(3곳) 실패 `WITHDRAWAL_CHECK_UNAVAILABLE` |
| `NOTICE_PENDING` | 안내 메일 발송 실패 `MAIL_FAILED` |
| 어느 단계든 | 처리 중 오류 `PROCESS_FAILED` · 상태가 바뀜 `STATE_CHANGED` |

재로그인은 `FAILED`도 무효로 하므로, 회원이 다시 카카오로 로그인했다면 이 절차 없이 끝납니다.

## 확인 순서

| 순서 | 할 일 | 보는 곳 |
|---|---|---|
| 1 | 기록의 `status` · `resume_state` · `last_error` · `failure_count` · `attempt_no` · `member_id` · `unlink_version` · `handles_withdrawal` | member DB `member_kakao_unlink_receipt` (읽기만) |
| 2 | 원인이 끝났는지: 카카오 API · market · payment · settlement · SMTP 장애가 아직 이어지는지 | 알림 · Loki |
| 3 | 회원의 `status` · `token_version` (연결된 회원이 없으면 `provider = 'KAKAO'`이고 `provider_id`가 같은 회원) | member DB `member` (읽기만) |

## 처리

| 확인 결과 | 처리 |
|---|---|
| 원인이 아직 이어짐 | 아무것도 하지 않음. `FAILED` 그대로 두고 원인이 끝난 뒤 다시 확인 |
| 원인이 끝났고 그 연결 해제가 아직 유효함 | **재개** 스크립트 |
| 그 연결 해제가 더 이상 유효하지 않음이 확인됨 | **종료** 스크립트 |

스크립트는 아래 조건을 스스로 다시 확인하고, 맞지 않으면 예외로 전부 롤백합니다(기록은 `FAILED` 그대로).

| 멈춘 단계 | 재개 조건 | 종료 조건 → 결과 |
|---|---|---|
| `PENDING` | 회원이 있음 (`ACTIVE` · `INACTIVE` 모두 정상: 아직 `INACTIVE`로 바꾸기 전 단계) | 회원이 없거나 탈퇴 → `IGNORED` (`last_error = MEMBER_NOT_FOUND`, 처리기와 같음) |
| `INACTIVATED` · `NOTICE_PENDING` | 회원이 `INACTIVE`이고 `token_version = unlink_version` (`NOTICE_PENDING`은 자동 탈퇴 처리 기록일 때만) | 재연결(`ACTIVE`) · 탈퇴 · 버전 불일치 → `INVALIDATED` (오류 · 실패 횟수 · 처리 기록 표시는 그대로) |

| 스크립트 | 바꾸는 값 |
|---|---|
| 재개 `ops/kakao-unlink-resume.sql` | `status = resume_state`, `resume_state = NULL`, `failure_count = 0`, `attempt_no + 1`, `lease_until = NULL`, `next_attempt_at = DB 현재 시각` |
| 종료 `ops/kakao-unlink-close.sql` | `status = IGNORED` 또는 `INVALIDATED`, `resume_state = NULL`, `attempt_no + 1`, `lease_until = NULL`, `next_attempt_at = NULL` |

두 스크립트 모두 처리기와 같은 회원 → 기록 순서로 잠그고(잠금 대기 3초), 처리권(`attempt_no`)을 올려 이전 작업자가 결과를 덮어쓰지 못하게 합니다. 파일은 `services/member/src/main/resources/ops/`에 있고 앱 시작 경로에는 연결하지 않습니다. 같은 파일을 통합 테스트(`KakaoUnlinkOpsScriptIntegrationTest`)가 실행합니다.

## 실행

배포된 버전과 같은 커밋의 스크립트 파일로, 운영 DB 접근 권한이 있는 사람만 실행합니다. 기록 id는 SQL 파일을 고치지 않고 트랜잭션 로컬 설정으로 넘깁니다.

```bash
psql "<member DB 접속 정보>" -v ON_ERROR_STOP=1 --single-transaction \
  -c "SELECT set_config('replant.ops.receipt_id', '<기록 id>', true)" \
  -f services/member/src/main/resources/ops/kakao-unlink-resume.sql
```

종료는 마지막 파일만 `kakao-unlink-close.sql`로 바꿉니다. 끝나면 별도로 조회해 최종 상태를 확인합니다.

```sql
SELECT id, status, resume_state, attempt_no, failure_count, last_error, next_attempt_at
  FROM member_kakao_unlink_receipt WHERE id = <기록 id>;
```

| 상황 | 할 일 |
|---|---|
| 스크립트가 `NOTICE` 출력 | "실행 결과"일 뿐 커밋 성공의 증거가 아님. 위 조회로 확인 |
| `ERROR` 출력 | 전부 롤백됨. 메시지의 이유(회원 없음 · 상태가 유효함 · `FAILED`가 아님 · 잠금 대기 초과 등)를 확인 |
| 연결이 끊기는 등 결과 불명 | **다시 실행하지 말고 먼저 조회**. 이미 반영됐으면 재실행은 `FAILED`가 아니라 거절됨 |

## 작업 기록

실패 · 롤백까지 모두 남깁니다. 회원번호 · 이메일 · 인증 키는 넣지 않습니다.

| 항목 | 기록 내용 |
|---|---|
| 실행 정보 | 실행자 · 시각 · 대상 환경 · 사용한 스크립트 버전(커밋) |
| 대상 | 수신 기록 id |
| 작업 | 재개 또는 종료, 판단 근거 |
| 결과 | 변경 전후 상태 · 시도 번호 |
| 확인 | 커밋 성공 · 롤백 · 결과 불명, 최종 조회 결과 |

## 운영 전에 갖출 것

- ⬜ 작업 기록을 남길 운영 공간 (접근이 제한된 곳, 팀 확정)
- ⬜ 운영 DB에서 스크립트를 실행할 권한자 (팀 · 인프라 확정)
- ⬜ 관리 API (권한 확인 · 실행 이력, 기존 `KakaoUnlinkResumeService` 호출) — 후속 이슈
