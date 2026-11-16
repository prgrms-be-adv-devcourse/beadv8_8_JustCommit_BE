# 외부 API 목록

> 언제 읽나: 외부 API의 경로 · 담당 서비스를 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-16 · 관련: Miro 20 · 21 · 22, API 명세서(노션)

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
| POST | `users` | 회원가입 | 공개 |
| POST | `users/social` | 소셜 가입 완료 | 공개 |
| GET | `users/nicknameAvailability?nickname=` | 닉네임 중복 확인 | 공개 |
| GET | `users/phoneAvailability?phone=` | 휴대폰 중복 확인 | 공개 |
| GET | `users/me` | 내 정보 | 로그인 |
| PATCH | `users/me/nickname` | 닉네임 변경 (지금 닉네임과 같으면 그대로) | 로그인 |
| PATCH | `users/me/password` | 비밀번호 변경 → 모든 기기 로그아웃. 200이면 이 기기 Refresh 쿠키 만료 · 프론트는 Access를 지우고 로그인 화면으로, `revocationCompleted`는 서버 폐기 기록 완료 여부. 503 `RESULT_UNKNOWN`은 자동 재시도 금지. 같은 인증 버전에서 현재 비밀번호를 5회 틀리면 5번째까지 400, 이후 요청은 첫 오답부터 24시간이 되는 시점까지 429 `PASSWORD_CHANGE_LOCKED` + `Retry-After`(남은 초) | 로그인 (로컬 회원) |
| GET · POST | `users/me/addresses` | 배송지 목록 · 추가 | 로그인 |
| PATCH | `users/me/addresses/{id}/default` | 기본 배송지 변경 | 로그인 |
| PATCH · DELETE | `users/me/addresses/{id}` | 배송지 수정(보낸 항목만. 우편번호나 기본 주소가 바뀌면 상세 주소도 함께 보냄, 비우려면 `""`. 아무 항목도 없으면 400, 지금과 같은 값만 보내면 200 · 변경 · 이벤트 없음) · 삭제(기본 배송지는 409) | 로그인 |
| POST | `sellers` | 판매자 등록 | 로그인 |
| GET | `sellers/{sellerId}` | 판매자 공개 정보 (닉네임 · 소개글 · 등록일, 숫자 id만) | 공개 |
| PATCH | `sellers/me/intro` | 소개글 수정 (최대 500자, 빈 문자열은 삭제) | 로그인 (판매자만) |
| GET | `/oauth2/authorization/kakao` · `/login/oauth2/code/kakao` | 카카오 로그인 (Spring Security 처리, `/api/v1` 아님) | 공개 |

#### 이메일 인증 목적

| 요청 `purpose` | 동작 |
|---|---|
| 생략 · `SIGNUP` | 가입 (생략하면 기존 요청과 같게 가입으로 처리). 가입된 이메일 409 `DUPLICATE_EMAIL`, 발송 제한 429 `EMAIL_SEND_COOLDOWN` · `EMAIL_SEND_DAILY_LIMIT`(+ `Retry-After`), 확인 실패는 `EMAIL_CODE_MISMATCH` · `EMAIL_CODE_EXPIRED` · `EMAIL_CODE_ATTEMPTS_EXCEEDED` |
| `PASSWORD_RESET` | 비밀번호 재설정 (아래 응답 규칙) |
| `ACCOUNT_CHANGE` | 정의만 있음. 지금 호출하면 400 `EMAIL_PURPOSE_NOT_SUPPORTED` (계좌 변경 #413에서 연결) |

Redis 키의 목적 부분은 소문자 하이픈(`signup` · `password-reset` · `account-change`) → [Redis 키](redis-keys.md)

#### 비밀번호 재설정 · 가입 계정 안내

공통: 응답으로 가입 여부를 알 수 없게, **유효한 요청은** 대상 여부 · 이메일 발송 제한 · SMTP 실패와 관계없이 같은 200을 반환합니다. 입력 오류는 400, Redis · DB 장애는 서버 오류이고, Gateway IP 제한에 걸리면 429 `TOO_MANY_REQUESTS`입니다. 같은 이메일의 발송 예약 제한(60초 1회 · 첫 예약부터 24시간 5회)은 인증 메일과 가입 계정 안내가 공유하고, Gateway IP 제한은 경로마다 따로 셉니다.

| 단계 | 요청 | 동작 · 응답 |
|---|---|---|
| 재설정 인증번호 발송 | `auth/email/send` `{ email, purpose: PASSWORD_RESET }` | 200 "재설정 가능한 계정이면 인증번호를 보내드립니다." 메일은 활동 중인 로컬 회원에게만 |
| 재설정 인증번호 확인 | `auth/email/verify` `{ email, code, purpose: PASSWORD_RESET }` | 200 `data: { resetToken, expiresIn: 1800 }`. 불일치 · 만료 · 오답 한도 초과 · 대상 또는 인증 버전 불일치(번호를 받은 뒤 비밀번호가 바뀜)는 모두 400 `EMAIL_CODE_INVALID` (입력 검증 오류와 Redis · DB 장애는 별도) |
| 재설정 | `auth/passwordResets` `{ resetToken, newPassword }` (새 비밀번호는 가입과 같은 규칙) | 커밋되면 200 `data: { revocationCompleted }` (`false`면 비밀번호는 바뀌었고 토큰 폐기 기록 확인만 늦어짐). 토큰 없음 · 만료 · 이미 사용 · 토큰을 받은 뒤 비밀번호가 바뀜 · 회원 없음은 400 `INVALID_RESET_TOKEN`, 회원 잠금 대기 초과 409 `MEMBER_BUSY`, 커밋 결과 불명 503 `RESULT_UNKNOWN`(자동 재요청하지 않고 새 비밀번호로 로그인해 확인). 토큰은 꺼낼 때 지워지므로 그 뒤 처리가 실패해도 복구하지 않음 → 다시 재설정하려면 이메일 인증부터. 쿠키는 바꾸지 않음(이 브라우저에 다른 계정 쿠키가 있을 수 있음, 재설정한 계정의 Refresh는 서버에서 모두 무효) |
| 가입 계정 안내 | `auth/accountReminders` `{ email }` | 200 "가입된 계정이면 안내 메일을 보내드립니다." 메일은 활동 중인 회원 중 현재 지원하는 로그인 방식(이메일 · 카카오)으로 가입한 계정에만 |

### product

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| GET | `products` | 목록 · 검색 · 필터 | 공개 |
| POST | `products` | 등록 | SELLER |
| GET | `products/{id}` | 상세 | 공개 |
| PUT | `products/{id}` | 수정 | SELLER |
| DELETE | `products/{id}` | 삭제 | SELLER |
| PATCH | `products/{id}/hidden` | 숨김 | SELLER |
| GET | `products/me` · `products/me/counts` | 내 상품 · 상태별 개수 | SELLER |
| POST | `products/images/uploadUrls` | 사진 Presigned URL | SELLER |
| GET | `products/{id}/imageReviews` | 내 상품 검수 기록 (상태 · 반려 사유 · 남은 재검수 횟수 · 이의 신청 · 재요청 가능 여부) | SELLER |
| POST | `products/{id}/imageReviews` | 기한 초과(`REVIEW_EXPIRED`) 상품 같은 내용으로 다시 검수 요청 | SELLER |
| POST | `products/{id}/appeals` | 자동 반려 이의 신청 (`memo` 필수, 200자). 409 `APPEAL_NOT_ALLOWED` · `APPEAL_ALREADY_USED` · `REVIEW_PENDING_LIMIT_EXCEEDED` | SELLER |
| GET | `species` · `species/{id}` | 품종 목록 · 상세 (학명 · 별칭) | 공개 |
| GET | `admin/imageReviews?status=MANUAL&page&size` | 관리자 확인 대기 목록, 오래된 순 (사진 · 엔진 결과 · 판매자 품종 · 관리자 확인 사유 · 기한 `expiresAt` · 12시간 지연 `delayed`) | ADMIN |
| PATCH | `admin/imageReviews/{id}` | 관리자 처리 `decision`(`APPROVED` · `REJECTED`) · `rejectReason` · `memo`(`OTHER`면 필수 200자, 다른 사유 · 통과면 저장하지 않음). 409 `REVIEW_ALREADY_PROCESSED` · `REVIEW_OUTDATED`, 400 `INVALID_REJECT_REASON` | ADMIN |

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
| member | 계좌 변경 · 탈퇴 | 노션 2.1 ~ 2.5 |
| market | 판매자 수락 · 거절 (판매자 주문 › 상품 트리 체크) | 판매 기록 화면 |
| market | 반품 신청 (사유) · 판매자 승인 · 거절 | 노션 7절 |
| market | 리뷰 작성 · 삭제 · 판매자 리뷰 목록 (태그 집계) | 노션 6절 |
| payment | 충전 취소 · 인출 · 탈퇴 잔액 정리 | 노션 4.3 · 4.6 · 4.7 |
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
