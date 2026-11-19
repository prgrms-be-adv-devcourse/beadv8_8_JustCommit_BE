# 외부 API 목록

> 언제 읽나: 외부 API의 경로 · 담당 서비스를 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-19 · 관련: Miro 20 · 21 · 22, API 명세서(노션)

## 서비스별 목록

경로 앞 `/api/v1` 생략. 권한: 공개 / 로그인 / SELLER / ADMIN

### member

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | `auth/email/send` | 인증번호 발송 (`purpose` → [이메일 인증 목적](#이메일-인증-목적)) | 공개 |
| POST | `auth/email/verify` | 인증번호 확인 (재설정 목적이면 재설정 토큰 응답) | 공개 |
| POST | `auth/passwordResets` | 비밀번호 재설정 → [응답 규칙](#비밀번호-재설정--가입-계정-안내) | 공개 |
| POST | `auth/accountReminders` | 가입 계정 안내 (아이디 찾기) → [응답 규칙](#비밀번호-재설정--가입-계정-안내) | 공개 |
| POST | `auth/login` | 로그인 | 공개 |
| POST | `auth/logout` | 로그아웃 | 공개(Refresh 쿠키 · 선택 `Authorization`) |
| POST | `auth/reissue` | 토큰 재발급 | 공개(Refresh 쿠키) |
| POST | `users` | 회원가입 (30일 안에 탈퇴한 이메일 · 휴대폰이면 409 `REJOIN_RESTRICTED`) | 공개 |
| POST | `users/social` | 소셜 가입 완료 (30일 안에 탈퇴한 이메일 · 휴대폰이면 409 `REJOIN_RESTRICTED`) | 공개 |
| GET | `users/nicknameAvailability?nickname=` | 닉네임 중복 확인 | 공개 |
| GET | `users/phoneAvailability?phone=` | 휴대폰 중복 확인 | 공개 |
| GET | `users/me` | 내 정보 | 로그인 |
| PATCH | `users/me/nickname` | 닉네임 변경 (지금 닉네임과 같으면 그대로) | 로그인 |
| PATCH | `users/me/password` | 비밀번호 변경 → 모든 기기 로그아웃. 200이면 이 기기 Refresh 쿠키 만료 · 프론트는 Access를 지우고 로그인 화면으로, `revocationCompleted`는 서버 폐기 기록 완료 여부. 503 `RESULT_UNKNOWN`은 자동 재시도 금지. 같은 인증 버전에서 현재 비밀번호를 5회 틀리면 5번째까지 400, 이후 요청은 첫 오답부터 24시간이 되는 시점까지 429 `PASSWORD_CHANGE_LOCKED` + `Retry-After`(남은 초) | 로그인 (로컬 회원) |
| POST | `users/me/account/verifications` | 계좌 변경 인증번호 발송(회원 정보의 이메일로) → [계좌 변경](#계좌-변경) | 로그인 |
| POST | `users/me/account/changeTokens` | 인증번호 확인 → 계좌 변경 토큰 | 로그인 |
| PATCH | `users/me/account` | 계좌 변경 | 로그인 |
| GET | `users/me/withdrawal` | 탈퇴 가능 여부 · 차단 사유 (화면 안내용) → [회원 탈퇴](#회원-탈퇴) | 로그인 |
| POST | `users/me/withdrawal/verifications` | 탈퇴 인증번호 발송(회원 정보의 이메일로, 카카오 회원만) → [회원 탈퇴](#회원-탈퇴) | 로그인 |
| POST | `users/me/withdrawal/tokens` | 인증번호 확인 → 탈퇴 토큰 | 로그인 |
| POST | `users/me/withdrawal` | 탈퇴 → [회원 탈퇴](#회원-탈퇴) (Gateway 응답 타임아웃 20초) | 로그인 |
| GET · POST | `users/me/addresses` | 배송지 목록 · 추가 | 로그인 |
| PATCH | `users/me/addresses/{id}/default` | 기본 배송지 변경 | 로그인 |
| PATCH · DELETE | `users/me/addresses/{id}` | 배송지 수정(보낸 항목만. 우편번호나 기본 주소가 바뀌면 상세 주소도 함께 보냄, 비우려면 `""`. 아무 항목도 없으면 400, 지금과 같은 값만 보내면 200 · 변경 · 이벤트 없음) · 삭제(기본 배송지는 409) | 로그인 |
| GET | `sellers/consent-policy` | 판매자 등록 동의 문구 (`version` · `title` · `content` 줄바꿈 포함 일반 텍스트) | 로그인 |
| POST | `sellers` | 판매자 등록 (`reviewConsentAgreed`=true · `reviewConsentVersion` 필수. 누락 · 미동의 400, 현재 버전이 아니면 409 `REVIEW_CONSENT_VERSION_MISMATCH`) | 로그인 |
| GET | `sellers/{sellerId}` | 판매자 공개 정보 (닉네임 · 소개글 · 등록일, 숫자 id만) | 공개 |
| PATCH | `sellers/me/intro` | 소개글 수정 (최대 500자, 빈 문자열은 삭제) | 로그인 (판매자만) |
| GET | `/oauth2/authorization/kakao` · `/login/oauth2/code/kakao` | 카카오 로그인 (Spring Security 처리, `/api/v1` 아님) | 공개 |
| GET · POST | `webhooks/kakao/unlink` | 카카오 연결 해제 웹훅 (`Authorization: KakaoAK {어드민 키}`) → [카카오 연결 해제 웹훅](#카카오-연결-해제-웹훅). ⬜ 미구현(#422) | 공개 |

#### 이메일 인증 목적

| 요청 `purpose` | 동작 |
|---|---|
| 생략 · `SIGNUP` | 가입 (생략하면 기존 요청과 같게 가입으로 처리). 가입된 이메일 409 `DUPLICATE_EMAIL`, 발송 제한 429 `EMAIL_SEND_COOLDOWN` · `EMAIL_SEND_DAILY_LIMIT`(+ `Retry-After`), 메일 작업 제출 거절 500 `EMAIL_SEND_FAILED`(→ [메일 발송](#메일-발송)), 확인 실패는 `EMAIL_CODE_MISMATCH` · `EMAIL_CODE_EXPIRED` · `EMAIL_CODE_ATTEMPTS_EXCEEDED` |
| `PASSWORD_RESET` | 비밀번호 재설정 (아래 응답 규칙) |
| `ACCOUNT_CHANGE` | 공개 경로에서는 받지 않음(400 `EMAIL_PURPOSE_NOT_SUPPORTED`). 계좌 변경 인증은 로그인 필수 전용 경로로 → [계좌 변경](#계좌-변경) |
| `WITHDRAWAL` | 공개 경로에서는 받지 않음(400 `EMAIL_PURPOSE_NOT_SUPPORTED`). 탈퇴 인증은 로그인 필수 전용 경로로 → [회원 탈퇴](#회원-탈퇴) |

Redis 키의 목적 부분은 소문자 하이픈(`signup` · `password-reset` · `account-change` · `withdrawal`) → [Redis 키](redis-keys.md)

#### 메일 발송

인증 메일(가입 · 비밀번호 재설정 · 계좌 변경 · 탈퇴)과 가입 계정 안내 메일은 요청 안에서 SMTP를 기다리지 않고 member의 메일 전용 실행기에서 보냅니다 (#421). 요청은 발송 예약 · 인증번호 저장(인증 메일만)까지 마치고 메일 작업을 제출한 뒤 응답하므로, 성공 응답은 발송 완료가 아니라 발송 요청 접수입니다(가입 · 계좌 변경 문구 "인증번호 발송을 요청했습니다."). 메일함 도착 시간은 SMTP에 달려 있습니다.

| 상황 | 가입 · 계좌 변경 · 탈퇴 | 비밀번호 재설정 · 가입 계정 안내 |
|---|---|---|
| 메일 작업 제출 거절 (실행기 대기열이 가득 참 · 종료 중 등) | 응답 전에 이 요청의 예약을 정리하고 500 `EMAIL_SEND_FAILED` | 응답 전에 이 요청의 예약을 정리하고 같은 200 |
| 제출 뒤 SMTP 실패 | 응답(200)과 별개로 메일 스레드가 이 요청의 예약을 정리 | 같음 |
| 제출 뒤 대기 중 인증번호가 교체 · 만료 · 사용됨 | 메일 스레드가 SMTP 호출 직전에 확인하고 발송을 건너뜀, 예약은 그대로 | 비밀번호 재설정은 같음. 가입 계정 안내는 인증번호가 없어 확인하지 않음 |

메일 스레드는 SMTP 호출 직전에 Redis에서 현재 인증번호가 해당 요청의 것인지 확인합니다. 번호가 교체됐거나 키가 없으면 발송을 건너뛰고 예약은 그대로 둡니다. 확인 직후 번호가 바뀌는 경우는 남습니다.

정리 대상은 인증 메일이면 이 요청이 만든 인증번호 · 재발송 제한 · 하루 발송 횟수, 가입 계정 안내면 재발송 제한 · 하루 발송 횟수입니다. 요청 식별자 기준이라 그 사이 다른 요청이 만든 값은 지우지 않습니다 → [Redis 키](redis-keys.md). SMTP 실패는 작업 제출 후 HTTP 응답과 별개로 메일 스레드에서 처리되므로, 예약 정리가 완료되면 같은 이메일로 다시 요청할 수 있고, 서버가 자동으로 다시 보내지는 않습니다. 응답으로는 SMTP 실패를 알 수 없어, 메일이 오지 않으면 다시 요청하도록 안내합니다. 접수 직후 서버가 재시작되면 대기 중이던 메일은 보내지지 않고, 그 요청의 예약은 그대로 남습니다(재발송 제한 60초가 끝나야 다시 요청할 수 있고, 하루 발송 횟수에도 1회로 남음).

#### 비밀번호 재설정 · 가입 계정 안내

재설정 인증번호 발송과 가입 계정 안내는 응답으로 가입 여부를 알 수 없게, **유효한 요청에 대해** 대상 여부 · 이메일 발송 제한 · 메일 작업 제출 거절 · SMTP 실패와 관계없이 같은 200을 반환합니다. 입력 오류는 400, Redis · DB 장애는 서버 오류이고, Gateway IP 제한에 걸리면 429 `TOO_MANY_REQUESTS`입니다. 같은 이메일의 발송 예약 제한 중 60초 1회는 인증 메일과 가입 계정 안내가 공유하고, 첫 예약부터 24시간 5회는 가입 인증 · 비밀번호 재설정 · 가입 계정 안내 · 계좌 변경 · 탈퇴별로 따로 셉니다. Gateway IP 제한은 경로마다 따로 셉니다.

| 단계 | 요청 | 동작 · 응답 |
|---|---|---|
| 재설정 인증번호 발송 | `auth/email/send` `{ email, purpose: PASSWORD_RESET }` | 200 "재설정 가능한 계정이면 인증번호를 보내드립니다." 메일은 활동 중인 로컬 회원에게만 |
| 재설정 인증번호 확인 | `auth/email/verify` `{ email, code, purpose: PASSWORD_RESET }` | 200 `data: { resetToken, expiresIn: 1800 }`. 불일치 · 만료 · 오답 한도 초과 · 대상 또는 인증 버전 불일치(번호를 받은 뒤 비밀번호가 바뀜)는 모두 400 `EMAIL_CODE_INVALID` (입력 검증 오류와 Redis · DB 장애는 별도) |
| 재설정 | `auth/passwordResets` `{ resetToken, newPassword }` (새 비밀번호는 가입과 같은 규칙) | 커밋되면 200 `data: { revocationCompleted }` (`false`면 비밀번호는 바뀌었고 토큰 폐기 기록 확인만 늦어짐). 토큰 없음 · 만료 · 이미 사용 · 토큰을 받은 뒤 비밀번호가 바뀜 · 회원 없음은 400 `INVALID_RESET_TOKEN`, 회원 잠금 대기 초과 409 `MEMBER_BUSY`, 커밋 결과 불명 503 `RESULT_UNKNOWN`(자동 재요청하지 않고 새 비밀번호로 로그인해 확인). 토큰은 꺼낼 때 지워지므로 그 뒤 처리가 실패해도 복구하지 않음 → 다시 재설정하려면 이메일 인증부터. 쿠키는 바꾸지 않음(이 브라우저에 다른 계정 쿠키가 있을 수 있음, 재설정한 계정의 Refresh는 서버에서 모두 무효) |
| 가입 계정 안내 | `auth/accountReminders` `{ email }` | 200 "가입된 계정이면 안내 메일을 보내드립니다." 메일은 활동 중인 회원 중 현재 지원하는 로그인 방식(이메일 · 카카오)으로 가입한 계정에만 |

#### 계좌 변경

로그인한 회원이 이메일 재인증으로 받은 일회용 토큰으로 환급 계좌를 바꿉니다. 이메일은 요청으로 받지 않고 회원 정보의 값을 씁니다. 발송 제한은 60초 1회(이메일 공유) · 24시간 5회(계좌 변경 따로)이고, 로그인한 본인이라 429 `EMAIL_SEND_COOLDOWN` · `EMAIL_SEND_DAILY_LIMIT` + `Retry-After`로 알립니다.

| 단계 | 요청 | 동작 · 응답 |
|---|---|---|
| 인증번호 발송 | `users/me/account/verifications` (본문 없음) | 200 `data: { expiresIn: 300, resendAvailableIn: 60 }`. 발송 당시 회원 id · 인증 버전을 번호에 묶음. 활동 중인 회원이 아니면 404 `MEMBER_NOT_FOUND`, 메일 작업 제출 거절 500 `EMAIL_SEND_FAILED`(→ [메일 발송](#메일-발송)) |
| 변경 토큰 발급 | `users/me/account/changeTokens` `{ code }` | 200 `data: { accountChangeToken, expiresIn: 600 }`. 틀림 · 만료 · 오답 한도는 `EMAIL_CODE_MISMATCH` · `EMAIL_CODE_EXPIRED` · `EMAIL_CODE_ATTEMPTS_EXCEEDED`. 번호는 맞지만 발송 뒤 회원 · 인증 버전 · 상태가 바뀌었으면 400 `ACCOUNT_VERIFICATION_INVALID` |
| 계좌 변경 | `users/me/account` `{ accountChangeToken, bankCode, accountNo, accountHolder }` (계좌 값은 가입과 같은 규칙) | 200 "계좌가 변경되었습니다."(data 없음, 바뀐 계좌는 `users/me`에서 뒤 4자리로 확인). 토큰 오류(없음 · 만료 · 이미 사용 · 다른 회원 · 발급 뒤 인증 버전 변경)는 400 `INVALID_ACCOUNT_CHANGE_TOKEN`이고 그 토큰은 다시 쓸 수 없음. 다른 회원이 쓰는 계좌 409 `DUPLICATE_ACCOUNT`, 잠금 대기 초과 409 `MEMBER_BUSY` |

토큰 처리: 요청 형식 오류 · 회원 잠금 대기 초과 · 중복 사전 조회 실패는 토큰이 남아 같은 토큰으로 다시 시도할 수 있습니다. 변경 성공 · 같은 계좌(은행 · 계좌번호 · 예금주가 모두 같으면 200 · 변경 없음) · 토큰을 꺼낸 뒤의 DB UNIQUE 위반(동시 등록, 409) · 저장 실패는 토큰이 소모되어 이메일 인증부터 다시 해야 합니다.

외부 계좌 인증이 없어 계좌 실존 · 예금주 일치 · 소유 여부는 확인하지 않습니다. 화면에는 "계좌 변경 완료"로 표시합니다("계좌 인증 완료" 아님).

#### 회원 탈퇴

처리 순서 · 익명화 · 재가입 제한은 [탈퇴 흐름](../flows/signup-and-replica.md#직접-탈퇴)에 있습니다. 사전 확인(GET)은 화면 안내용이고, 최종 탈퇴(POST)에서 다시 확인합니다. 응답은 캐시하지 않습니다.

| 단계 | 요청 | 동작 · 응답 |
|---|---|---|
| 사전 확인 | `GET users/me/withdrawal` | 200 `data: { status, blockers }`. `status`는 `ALLOWED` · `BLOCKED`, `blockers`는 `[{ service, reasons, details }]`(`ALLOWED`면 빈 배열). 확인 실패는 503 `WITHDRAWAL_CHECK_UNAVAILABLE`(일부 서비스에서 받은 차단 사유는 보여 주지 않음) |
| 인증번호 발송 | `users/me/withdrawal/verifications` (본문 없음) | 카카오 회원만. 200 `data: { expiresIn: 300, resendAvailableIn: 60 }`. 발송 당시 회원 id · 인증 버전을 번호에 묶음. 발송 제한 · 메일 작업 제출 거절은 계좌 변경과 같음(60초 1회 이메일 공유, 24시간 5회 탈퇴 따로) |
| 탈퇴 토큰 발급 | `users/me/withdrawal/tokens` `{ code }` | 200 `data: { withdrawalToken, expiresIn: 600 }`. 틀림 · 만료 · 오답 한도는 `EMAIL_CODE_MISMATCH` · `EMAIL_CODE_EXPIRED` · `EMAIL_CODE_ATTEMPTS_EXCEEDED`. 번호는 맞지만 발송 뒤 회원 · 인증 버전 · 상태가 바뀌었으면 400 `WITHDRAWAL_VERIFICATION_INVALID` |
| 탈퇴 | `users/me/withdrawal` 로컬 `{ password }` · 카카오 `{ withdrawalToken }` | 아래 표 |

| 상황 | 응답 |
|---|---|
| 현재 비밀번호 틀림 | 400 `INVALID_CURRENT_PASSWORD` (비밀번호 변경과 실패 횟수 공유) |
| 비밀번호 오답 한도 초과 | 429 `PASSWORD_CHANGE_LOCKED` + `Retry-After`(남은 초). 비밀번호 변경과 같은 실패 횟수라 같은 코드를 쓰고, 메시지는 범용으로 바꿈: "현재 비밀번호를 여러 번 틀려 잠시 제한되었습니다. 비밀번호 재설정을 이용해 주세요." |
| 회원 유형과 다른 재확인 값(로컬이 토큰, 카카오가 비밀번호를 보냄) · 로컬 회원의 인증번호 발송 | 400 `BAD_REQUEST_DATA` |
| 탈퇴 토큰 오류(없음 · 만료 · 이미 사용 · 다른 회원 · 발급 뒤 인증 버전 변경) | 400 `INVALID_WITHDRAWAL_TOKEN` |
| 탈퇴 조건 미충족 | 409 `WITHDRAWAL_BLOCKED` (사유는 담지 않음, 화면은 사전 확인 GET을 다시 불러 표시) |
| 회원 잠금 대기 초과 | 409 `MEMBER_BUSY` |
| 탈퇴 가능 여부 확인 실패 | 503 `WITHDRAWAL_CHECK_UNAVAILABLE` |
| 카카오 연결 해제 실패 | 503 `SOCIAL_UNLINK_FAILED`. 회원 정보는 바뀌지 않았지만 탈퇴 토큰은 이미 소비돼 이메일 재인증 후 다시 시도해야 함. 카카오 연결이 이미 해제된 상태라면 다시 시도할 때 같은 회원번호의 `-101`을 성공으로 처리하므로 연결 해제 재호출은 안전 |
| 성공 | 200 "회원 탈퇴가 완료되었습니다." `data: { revocationCompleted }` + 이 기기 Refresh 쿠키 만료. `false`여도 탈퇴는 커밋됐고 폐기 기록만 재처리 대기 중 → 프론트는 Access를 지우고 로그아웃 상태로 이동 |
| 커밋 결과 불명 | 503 `RESULT_UNKNOWN`. 쿠키를 유지하고 자동 재요청하지 않음 (비밀번호 변경과 같음) |
| Gateway 응답 타임아웃 | 최종 탈퇴 POST는 Gateway 응답 타임아웃을 20초로 따로 둠(3곳 확인 · 카카오 연결 해제 · DB · Redis 처리 시간이 이어질 수 있어서). 11초 지연 응답이 정상 전달되는 것은 Gateway 통합 테스트로 확인(라우트 설정 확인이며 실제 탈퇴 처리 시간 측정은 아님). 전체 탈퇴 처리 시간은 #408 · #422 구현 뒤 최종 통합 단계에서 측정. 20초를 넘어 504가 나면 탈퇴 결과를 알 수 없으므로 자동 재요청하지 않음 |

토큰 처리: 확인 실패 · 차단 · 잠금 대기 초과처럼 토큰을 꺼내기 전에 끝나면 토큰이 남아 다시 쓸 수 있습니다. 카카오 연결 해제 직전에 꺼내므로 그 뒤의 실패(연결 해제 실패 · 저장 실패)는 토큰이 소모되어 이메일 인증부터 다시 해야 합니다.

#### 카카오 연결 해제 웹훅

⬜ 미구현(#422). 처리 흐름은 [탈퇴 흐름](../flows/signup-and-replica.md#카카오-연결-해제-웹훅)에 있습니다.

| 항목 | 내용 |
|---|---|
| 요청 | `GET` 쿼리 또는 `POST` 폼으로 `app_id` · `user_id`(카카오 회원번호) · `referrer_type` (카카오 공식 문서 「연결 해제 웹훅」) |
| 인증 | `Authorization: KakaoAK {어드민 키}`가 틀리면 401로 끝냄(수신 기록 없음) |
| 응답 | 인증에 성공하면 수신 기록을 저장한 뒤 3초 안에 200. 회원이 없거나 처리 대상이 아닌 사유여도 200 |
| 경로 | Gateway에서 market 라우트(`/api/v1/webhooks/**`)보다 앞에 member 라우트 · 공개 경로 추가 필요 (#422) |

⬜ 미정: 수신 기록 저장 실패 시 응답 → [미정 항목](../open-questions.md)

### product

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `products` | 목록 · 검색 · 필터 | 공개 |
| POST | `products` | 등록 | SELLER |
| GET | `products/{id}` | 상세 | 공개 |
| PUT | `products/{id}` | 수정 | SELLER |
| DELETE | `products/{id}` | 삭제 | SELLER |
| PATCH | `products/{id}/hidden` | 숨김 | SELLER |
| GET | `products/me` · `products/me/counts` | 내 상품 (지금 검수 버전의 표시 상태 `reviewStatus` · 반려 사유 `rejectReason`와 문구 `rejectMessage` · 판정 주체 `decidedBy`, 검수 기록이 없으면 null) · 상태별 개수 (+ 오늘 등록 수 `todayRegistered` / 한도 `dailyRegisterLimit` 20 · 오늘 사진 검수 수 `todayPhotoReviews` / 한도 `dailyPhotoReviewLimit` 30, 세는 기준은 등록 제한 · 하루 사진 검수 제한과 같음) | SELLER |
| POST | `products/images/uploadUrls` | 사진 Presigned URL | SELLER |
| GET | `products/{id}/imageReviews` | 내 상품 검수 기록 (상태 · 반려 사유 · 관리자 확인 기한 `expiresAt` · 남은 재검수 횟수 · 이의 신청 · 재요청 가능 여부) | SELLER |
| POST | `products/{id}/imageReviews` | 기한 초과(`REVIEW_EXPIRED`) 상품 같은 내용으로 다시 검수 요청 | SELLER |
| POST | `products/{id}/appeals` | 자동 반려 이의 신청 (`memo` 필수, 200자). 409 `APPEAL_NOT_ALLOWED` · `APPEAL_ALREADY_USED` · `REVIEW_PENDING_LIMIT_EXCEEDED` | SELLER |
| GET | `species` · `species/{id}` | 품종 목록 · 상세 (학명 · 별칭) | 공개 |
| GET | `admin/imageReviews?status=MANUAL&page&size` | 관리자 확인 대기 목록, 오래된 순 (사진 · 엔진 결과 · 판매자 품종 · 판매자 닉네임 `sellerNickname`(회원 복제본, 없으면 「알 수 없는 회원」) · 가격 `price` · 검수 요청 시각 `requestedAt` · 관리자 확인 사유 · 기한 `expiresAt` · 12시간 지연 `delayed`) | ADMIN |
| PATCH | `admin/imageReviews/{id}` | 관리자 처리 `decision`(`APPROVED` · `REJECTED`) · `rejectReason` · `memo`(`OTHER`면 필수 200자, 다른 사유 · 통과면 저장하지 않음). 409 `REVIEW_ALREADY_PROCESSED` · `REVIEW_OUTDATED`, 400 `INVALID_REJECT_REASON` | ADMIN |
| POST | `admin/products/searchIndex` | 상품 전체 색인: DB의 모든 상품을 OpenSearch에 다시 씀(삭제 상품은 문서 삭제). 응답 `indexed` · `deleted` · `skipped`(같거나 더 새 버전이 이미 있음) · `failed`, 여러 번 실행해도 결과가 같음 → [검색 · 추천](../flows/search-recommend.md) | ADMIN |

### market

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `cart` | 장바구니 | 로그인 |
| POST | `cart/items` | 담기 | 로그인 |
| DELETE | `cart/items/{productId}` | 빼기 | 로그인 |
| GET | `cart/recommendations` | 장바구니 추천 | 로그인 |
| POST | `orders/cart` | 장바구니 주문 | 로그인 |
| POST | `orders/{id}/pay` | 결제 | 로그인 |
| POST | `orders/{id}/cancel` | 전체 취소 | 로그인 |
| POST | `orders/items/{id}/cancel` | 상품 취소 | 로그인 |
| POST | `orders/items/{id}/confirm` | 구매 확정 | 로그인 |
| GET | `orders` · `orders/{id}` | 구매 내역 · 상세 | 로그인 |
| GET | `orders/seller` · `orders/seller/{id}` | 판매 내역 · 상세 | SELLER |
| POST | `webhooks/courier` | 택배사 웹훅 (`X-Api-Key`) | 공개 |

### payment · settlement · notification

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `wallet` · `wallet/transactions` | 예치금 · 거래 내역 | 로그인 |
| POST | `wallet/charges` | 충전 요청 | 로그인 |
| POST | `wallet/charges/{id}/confirm` | 충전 승인 (토스) | 로그인 |
| GET | `settlements` | 정산 내역 | SELLER |
| GET | `notifications` | 알림 목록 | 로그인 |
| GET | `notifications/unreadCount` | 안 읽은 수 | 로그인 |
| PATCH | `notifications` | 모두 읽음 | 로그인 |
| PATCH | `notifications/{id}` | 읽음 | 로그인 |
| GET | `notifications/subscribe` | SSE 구독 | 로그인 |

## 추가 예정

⬜ 경로는 담당이 확정합니다 ([API 작성 규칙](../conventions/api-style.md)을 따름).

| 서비스 | 기능 | 설명 |
|---|---|---|
| market | 판매자 수락 · 거절 (판매자 주문 › 상품 트리 체크) | 판매 기록 화면 |
| market | 반품 신청 (사유) · 판매자 승인 · 거절 | 노션 7절 |
| market | 리뷰 작성 · 삭제 · 판매자 리뷰 목록 (태그 집계) | 노션 6절 |
| payment | 인출 | 노션 4.6 |
| notification | `GET notifications?read=` (커서 페이지네이션) | 삭제 API 없음 |
| ai | 챗봇 | ⬜ 범위 보류 |

## 경로 개선안

| 지금 | 개선안 |
|---|---|
| `POST orders/cart` | `POST orders` `{"cartItemIds":[...]}` |
| `POST orders/{id}/pay` | `POST orders/{id}/payments` |
| `POST orders/{id}/cancel` | `PATCH orders/{id}` `{"status":"CANCELLED"}` |
| `POST orders/items/{id}/cancel` | `PATCH orderItems/{id}` `{"status":"CANCELLED"}` |
| `POST orders/items/{id}/confirm` | `PATCH orderItems/{id}` `{"status":"CONFIRMED"}` |
| `PATCH users/me/addresses/{id}/default` | `PATCH users/me/addresses/{id}` `{"isDefault":true}` |

🟨 팀이 고를 것: `products/me` → `users/me/products`, `orders/seller` → `sellerOrders`, `cart/items/{productId}` 유지 또는 cartItemId. ⬜ 바꾸는 시점(서비스 분리 때 · 프론트 동시 수정)은 미정.
