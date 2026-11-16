# member 서비스

> 언제 읽나: member 서비스 코드를 고치거나, 이 서비스가 무엇을 갖고 누구와 통신하는지 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-16 · 관련: Miro 5 · 6, A1, 노션 기획안 「MSA 분리 지점」

## 하는 일

| 기능 | 내용 |
|---|---|
| 가입 · 로그인 | 이메일 인증 가입, 카카오 · 네이버(카카오 구조 재사용) 로그인, 가입 계정 안내(아이디 찾기), 비밀번호 재설정 |
| 토큰 | RS256 개인키로 Access 30분 · Refresh 1일 발급, 재발급마다 Refresh 교체, 로그아웃 · 회원 단위 폐기 → [인증 규칙](../conventions/auth.md) |
| 회원 정보 | 닉네임 · 비밀번호 · 계좌(이메일 재인증) 변경, 배송지(최대 4개), 판매자 등록 · 소개글 |
| 계좌 | AES-256-GCM 암호화, HMAC으로 중복 막음, payment에 내부 API로 제공 (복제 안 함) |
| 탈퇴 | 3곳 병렬 확인 · 소셜 연결 해제 · 익명화 · 30일 재가입 불가 → [가입 · 탈퇴 흐름](../flows/signup-and-replica.md#탈퇴) |
| 회원 정보 전파 | 회원 정보 · 판매자 배송지 목록 변경을 이벤트로 발행 |

## 소유 테이블

| 테이블 | 설명 |
|---|---|
| `MEMBER` | 회원 (계좌 암호문 · HMAC · 인증 버전 포함) |
| `ADDRESS` | 배송지 (최대 4개, 기본 1개) |
| `SELLER` | 판매자 등록 정보 · 소개글 · 사진 이용 동의 |
| ⬜ 탈퇴 기록 (이름 미정) | 이메일 · 휴대폰 HMAC + 탈퇴 시각 (30일 보관) |
| `OUTBOX_EVENT` | 발행 대기 이벤트 (`common-messaging-outbox`, Flyway V2) |
| `MEMBER_TOKEN_REVOCATION` | 탈퇴 · 비밀번호 변경 · 재설정 토큰 폐기 작업 (끊은 시각 · 보관 기한 · Redis 기록 상태, Flyway V4, 재설정 사유 V5) |

## 외부 API

`auth/email/send` · `auth/email/verify` · `auth/login` · `auth/logout` · `auth/reissue` · `users` · `users/social` · `users/nicknameAvailability` · `users/phoneAvailability` · `users/me` · `users/me/nickname` · `users/me/password` · `users/me/addresses` · `users/me/addresses/{id}/default` · `sellers` · `sellers/{sellerId}` · `sellers/me/intro` + 카카오 로그인 2개 → [외부 API 목록](../reference/external-apis.md)

## 제공하는 내부 API

| API | 부르는 서비스 | 상태 |
|---|---|---|
| `GET /internal/v1/members/{memberId}/account` 계좌 조회 | payment (인출 신청 때) | ✅ member 구현 · ⬜ payment 연동(#388) |

상세 → [내부 API 목록](../reference/internal-apis.md)

## 호출하는 내부 API

| 대상 | 용도 | 상태 |
|---|---|---|
| market · payment · settlement | 탈퇴 확인 (병렬, Fail-closed): `GET /internal/v1/{orders, payments, settlements}/members/{memberId}/withdrawalCheck`, 공통 응답 `hasWithdrawalBlocker` · `blockingReasons` · `details`, 결과 `ALLOWED` · `BLOCKED` · `UNAVAILABLE` (탈퇴는 `ALLOWED`일 때만) | ✅ member 클라이언트 · 결과 취합 구현 · ⬜ 실제 서비스 연동 (market #278 · payment #329 · settlement #441) |
| 카카오 | 연결 해제 (실패하면 탈퇴 막음) | ✅ |

## 발행 이벤트

Outbox(`common-messaging-outbox`)로 발행합니다. Key는 `memberId`입니다.

| 토픽 | 이벤트 | 발행 시점 | 상태 |
|---|---|---|---|
| `member.events.v1` | `MemberCreated` | 로컬 · 소셜 가입 | ✅ |
| `member.events.v1` | `MemberUpdated` | 판매자 등록 · 닉네임 변경 (바뀐 경우만) | ✅ |
| `member.events.v1` | `MemberWithdrawn` | 탈퇴 | ⬜ record만 (탈퇴 기능과 함께) |
| `member.address.events.v1` | `MemberAddressChanged` (판매자 배송지 목록, 암호화) | 판매자 등록 · 배송지 추가 · 기본 배송지 변경 · 수정 · 삭제 (판매자만, 바뀐 뒤 전체 목록. 수정은 실제로 바뀐 경우만) | ✅ |

payload (받는 쪽은 upsert, `occurredAt`이 저장된 값보다 오래되면 무시):

| 이벤트 | payload |
|---|---|
| `MemberCreated` · `MemberUpdated` | `{ "memberId": 7, "nickname": "초록손", "role": "MEMBER", "seller": false }` (바뀐 칸만이 아니라 바뀐 뒤 전체 값) |
| `MemberWithdrawn` | `{ "memberId": 7 }` |
| `MemberAddressChanged` | `{ "memberId": 7, "encryptedAddresses": "v1.k1:..." }` (암호화 전 주소 형식 → [Kafka 토픽 표](../reference/kafka-topics.md)) |

- `role`: `MEMBER` · `SELLER` · `ADMIN`
- `seller`: 판매자 등록 여부. ADMIN도 판매자가 될 수 있어 `role`과 따로 보냄 (ADMIN 변경은 지금 SQL이라 이벤트 없음)

상세 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 구독 이벤트

없음

## 스케줄러

| 작업 | 주기 | 하는 일 |
|---|---|---|
| 토큰 폐기 재처리 | 10초 | Redis 기록을 확인하지 못한 폐기 작업을 보관 기한까지 다시 기록 → [스케줄러](../reference/schedulers.md) |

## 정책

노션 「정책 정리」 2절 기준입니다.

| 주제 | 규칙 |
|---|---|
| 가입 정보 | 이메일 · 비밀번호 · 닉네임 · 휴대폰 · 환급 계좌 · 배송지 필수 (소셜도 같음, 임시 가입 토큰 10분) |
| 입력 규칙 | 비밀번호 8~20자 영문 · 숫자 각 1개 이상(BCrypt) · 닉네임 2~8자 · 휴대폰 01로 시작 11자리 |
| 이메일 인증 | 6자리 5분, 5번 틀리면 폐기(다시 받으려면 재발송), 인증 완료 30분. 목적(가입 · 비밀번호 재설정 · 계좌 변경)별로 따로 저장해 다른 목적의 인증을 쓰지 못함(계좌 변경은 #413에서 연결). 발송 예약은 이메일 단위로 60초에 한 번 · 첫 예약부터 24시간 5회이고 가입 계정 안내와 공유. 가입 발송은 제한을 429로 알리고, 재설정 발송 · 가입 계정 안내는 같은 200 → 요청 · 응답 [외부 API](../reference/external-apis.md#비밀번호-재설정--가입-계정-안내), 키 [Redis 키](../reference/redis-keys.md) |
| 가입 계정 안내 (아이디 찾기) | 이메일을 받아, 활동 중인 회원 중 현재 지원하는 로그인 방식(이메일 · 카카오)으로 가입한 계정에 로그인 방식을 메일로 안내(이메일 로그인은 비밀번호 재설정 안내 포함). 응답으로 가입 여부를 알 수 없게 유효한 요청은 같은 200 → [외부 API](../reference/external-apis.md#비밀번호-재설정--가입-계정-안내) |
| 중복 | 이메일 · 닉네임 · 휴대폰 · 계좌(HMAC)는 회원마다 달라야 함 |
| 소셜 | 이메일 동의 필수, 같은 이메일의 로컬 회원이 있으면 가입 막음 |
| 로그인 실패 | 무엇이 틀렸는지 구분하지 않고 `LOGIN_FAILED` |
| 권한 | 가입하면 `MEMBER`, 판매자 등록하면 바로 `SELLER` (사진 외부 전송 · 학습 이용 동의 필수, 내용 · 버전 · 시각 기록) |
| 여러 기기 | 동시 로그인 허용 |
| 회원 행 잠금 | 로그인 · 소셜 로그인 · 재발급 · 토큰 폐기 · 배송지 추가 · 기본 변경 · 수정 · 삭제 · 판매자 등록 · 닉네임 변경이 같은 회원 행 잠금(`MemberLocker`)을 씀 (잠금 조회에만 대기 3초, 넘으면 409 `MEMBER_BUSY`, 저장 · 이벤트 없이 롤백. 잠금을 잡은 뒤 저장 쿼리에는 기존 `lock_timeout`이 적용됨) → [인증 규칙](../conventions/auth.md#토큰-폐기) |
| 비밀번호 변경 | 현재 비밀번호 확인 후 변경하고 모든 기기 로그아웃(토큰 폐기 → [인증 규칙](../conventions/auth.md#토큰-폐기)). 현재 비밀번호가 틀리면 400 `INVALID_CURRENT_PASSWORD`, 새 비밀번호가 같으면 400 `SAME_AS_CURRENT_PASSWORD`, 소셜 회원은 400 `PASSWORD_NOT_SET`. 변경이 커밋되면 200(이 기기 Refresh 쿠키 만료, `revocationCompleted`로 폐기 기록 완료 여부를 따로 전달, `false`면 다른 기기 로그아웃이 늦어질 수 있음), 커밋 결과 불명은 503 `RESULT_UNKNOWN`(자동 재시도 금지, 새 비밀번호로 로그인해 확인). 같은 인증 버전에서 현재 비밀번호를 5회 틀리면 이후 변경 요청을 막음(5번째 오답도 400, 이후는 첫 오답부터 24시간이 되는 시점까지 429 `PASSWORD_CHANGE_LOCKED` + `Retry-After`, 재설정으로 안내). 변경 · 재설정이 커밋되면 실패 횟수 초기화 |
| 비밀번호 재설정 | 재설정 목적 이메일 인증 → 일회용 재설정 토큰(30분, 인증번호를 보낼 때의 인증 버전에 묶여 그 뒤 비밀번호가 바뀌면 쓸 수 없음) → 새 비밀번호 저장 · 모든 기기 로그아웃. 대상은 활동 중인 로컬 회원, 소셜 · 탈퇴 회원에게는 메일을 보내지 않음(응답은 같음) → [외부 API](../reference/external-apis.md#비밀번호-재설정--가입-계정-안내) |
| 계좌 변경 | 이메일 재인증 후 가능(소셜도 같음). 정산 대기 · 진행 중 인출이 있어도 가능하고 다른 서비스 확인 호출은 없음 (정산금은 지갑으로, 인출은 신청할 때의 계좌로 지급) |
| 계좌 표시 | 화면은 뒤 4자리만, 로그에 원문 금지, 복호화는 member 안에서만. payment에는 member가 복호화해 응답 (⬜ 운영 전송 보호 방식(서비스 간 HTTPS 또는 응답 암호화)은 payment · 인프라 담당과 정함) |
| 배송지 | 회원당 최대 4개 · 기본 1개(회원 행 잠금). 수정은 보낸 항목만 바꾸는 부분 수정(상세 주소 · 배송지 이름은 빈 문자열로 지움, 우편번호 · 주소는 함께, 우편번호나 기본 주소가 바뀌면 상세 주소도 함께 보냄(비우려면 `""`, 없으면 400 `ADDRESS_DETAIL_REQUIRED`), 기본 여부는 기본 변경 API로만). 기본 배송지는 삭제할 수 없음(409 `DEFAULT_ADDRESS_NOT_DELETABLE`, 다른 배송지를 기본으로 바꾼 뒤 삭제). 남의 배송지는 404. 배송지를 바꾸는 모든 경로(추가 · 기본 변경 · 수정 · 삭제 · 판매자 등록)가 회원 행을 먼저 잠근다 |
| 판매자 상점 | 판매자 정보(닉네임 · 소개글 · 등록일)는 누구나 봄(공개 경로는 Gateway · member 양쪽에 등록). 판매자가 아니거나 없는 회원 · 탈퇴한 판매자는 404 `SELLER_NOT_FOUND` (탈퇴한 판매자 표시는 탈퇴 Feature에서 다시 정할 수 있음). 소개글 500자는 판매자 본인만 수정(판매자 행이 없으면 403 `NOT_SELLER`), 빈 문자열은 삭제 |
| 탈퇴 | → [가입 · 탈퇴 흐름](../flows/signup-and-replica.md#탈퇴) |

⬜ 미정: 요청 수 제한 허용 횟수, 탈퇴 재확인 방식, 소셜 연결 해제 회원의 재로그인 → [미정 항목](../open-questions.md)

## 테스트 시나리오

S-A01 · S-A04 · S-A08 · S-A09 · S-B01 ~ S-B04 → [테스트 시나리오](../testing/scenarios.md)
