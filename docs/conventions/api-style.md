# 외부 API 작성 규칙

> 언제 읽나: 외부 API를 새로 만들거나 경로 · 응답 형식을 바꿀 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 20 · 21

## 경로 규칙

| 규칙 | 예 |
|---|---|
| URL에 동사를 쓰지 않는다 | `POST orders/{id}/pay` → `POST orders/{id}/payments` |
| 리소스는 복수형 | `orders`, `products`, `notifications` |
| 상태 변경은 `PATCH` + body | `PATCH orders/{id}` `{"status":"CANCELLED"}` |
| 여러 단어 경로는 camelCase | `nicknameAvailability`, `orderItems`, `imageReviews` |
| 관례로 굳은 경로는 예외 | `auth/login` · `logout` · `reissue` · `auth/email/send` · `verify` · `wallet/charges/{id}/confirm` · `products/{id}/hidden` · `PATCH notifications` · `cart`(단수) |

지금 경로를 위 규칙으로 바꾸는 안은 [외부 API 목록 「경로 개선안」](../reference/external-apis.md#경로-개선안)에 있습니다. 바꾸는 시점은 ⬜ 미정입니다.

## 응답 형식

모든 응답(에러 포함)은 `common-core`의 `ApiResponse`로 감쌉니다. Gateway가 내는 401 · 403 · 503도 같은 형식입니다.

```json
{ "status": 200, "message": "주문을 조회했습니다.", "data": { } }
{ "status": 404, "code": "PRODUCT_NOT_FOUND", "message": "상품을 찾을 수 없습니다." }
```

| 필드 | 설명 |
|---|---|
| `status` | HTTP 상태 숫자 |
| `code` | 에러일 때만, `ErrorCode` 이름 |
| `message` | 사용자에게 보여줄 문장 |
| `data` | 성공일 때만 (`null`이면 생략) |

목록 조회는 `common-web`의 `PagingRequest` · `PagingResponse`를 씁니다.

## 에러 코드

| 규칙 | 내용 |
|---|---|
| 예외 | `BusinessException(ErrorCode)`를 던지고 `GlobalExceptionHandler`가 `ApiResponse.error`로 바꿈 |
| 코드 위치 | 공통은 `CommonErrorCode`(`BAD_REQUEST_DATA`, `RESOURCE_NOT_FOUND` 등), 도메인은 서비스별 `{도메인}ErrorCode` (예: `ProductErrorCode.PRODUCT_HIDDEN`) |
| 이름 | 대문자 스네이크, 상황이 다르면 코드를 나눔 (`PRODUCT_NOT_FOUND`와 `PRODUCT_HIDDEN`) |
| 요청 DTO | `{도메인}{행위}Request` record, 검증 실패는 `INVALID_FIELD_ERROR` |

## 버전

외부 API는 모두 `/api/v1`을 유지합니다. 새 API도 `v1` 같은 경로 아래에 추가합니다 (`v2`를 만들지 않음). 내부 API는 `/internal/v1`입니다.
