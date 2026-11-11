# 외부 API 목록

> 언제 읽나: 외부 API의 경로 · 담당 서비스를 찾을 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-11 · 관련: Miro 20 · 21 · 22, API 명세서(노션)

## 서비스별 목록

경로 앞 `/api/v1` 생략. 권한: 공개 / 로그인 / SELLER / ADMIN

### member

| 메서드 | 경로 | 설명 | 권한 |
|---|---|---|---|
| POST | `auth/email/send` | 인증번호 발송 | 공개 |
| POST | `auth/email/verify` | 인증번호 확인 | 공개 |
| POST | `auth/login` | 로그인 | 공개 |
| POST | `auth/logout` | 로그아웃 | 공개(Refresh 쿠키) |
| POST | `auth/reissue` | 토큰 재발급 | 공개(Refresh 쿠키) |
| POST | `users` | 회원가입 | 공개 |
| POST | `users/social` | 소셜 가입 완료 | 공개 |
| GET | `users/checkNickname` | 닉네임 중복 확인 | 공개 |
| GET | `users/checkPhone` | 휴대폰 중복 확인 | 공개 |
| GET | `users/me` | 내 정보 | 로그인 |
| GET · POST | `users/me/addresses` | 배송지 목록 · 추가 | 로그인 |
| PATCH | `users/me/addresses/{id}/default` | 기본 배송지 변경 | 로그인 |
| POST | `sellers` | 판매자 등록 | 로그인 |
| GET | `/oauth2/authorization/kakao` · `/login/oauth2/code/kakao` | 카카오 로그인 (Spring Security 처리, `/api/v1` 아님) | 공개 |

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
| GET | `species` · `species/{id}` | 품종 목록 · 상세 (학명 · 별칭) | 공개 |
| GET | `admin/imageReviews?status=MANUAL` | 수동 검수 대기 목록 | ADMIN |
| PATCH | `admin/imageReviews/{id}` | 수동 검수 결과 | ADMIN |

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
| member | 아이디 찾기 · 비밀번호 재설정 · 비밀번호 변경 · 닉네임 변경 · 계좌 변경 · 배송지 수정 · 탈퇴 | 노션 2.1 ~ 2.5 |
| member | 판매자 상점 정보 · 소개글 수정 | 공개 조회 경로는 Gateway · member 양쪽에 등록 |
| product | 자동 반려 이의 신청 (메모 필수, 200자) | 노션 3.16 |
| market | 판매자 수락 · 거절 (판매자 주문 › 상품 트리 체크) | 판매 기록 화면 |
| market | 반품 신청 (사유) · 판매자 승인 · 거절 | 노션 7절 |
| market | 리뷰 작성 · 삭제 · 판매자 리뷰 목록 (태그 집계) | 노션 6절 |
| payment | 충전 취소 · 인출 · 탈퇴 잔액 정리 | 노션 4.3 · 4.6 · 4.7 |
| notification | `GET notifications?read=` (커서 페이지네이션) | 삭제 API 없음 |
| ai | 챗봇 | ⬜ 범위 보류 |

## 경로 개선안

| 지금 | 개선안 |
|---|---|
| `GET users/checkNickname` | `GET users/nicknameAvailability?nickname=` |
| `GET users/checkPhone` | `GET users/phoneAvailability?phone=` |
| `POST orders/cart` | `POST orders` `{"cartItemIds":[...]}` |
| `POST orders/{id}/pay` | `POST orders/{id}/payments` |
| `POST orders/{id}/cancel` | `PATCH orders/{id}` `{"status":"CANCELLED"}` |
| `POST orders/items/{id}/cancel` | `PATCH orderItems/{id}` `{"status":"CANCELLED"}` |
| `POST orders/items/{id}/confirm` | `PATCH orderItems/{id}` `{"status":"CONFIRMED"}` |
| `PATCH users/me/addresses/{id}/default` | `PATCH users/me/addresses/{id}` `{"isDefault":true}` |

🟨 팀이 고를 것: `products/me` → `users/me/products`, `orders/seller` → `sellerOrders`, `cart/items/{productId}` 유지 또는 cartItemId. ⬜ 바꾸는 시점(서비스 분리 때 · 프론트 동시 수정)은 미정.
