# 서비스 간 REST 규칙

> 언제 읽나: 다른 서비스를 REST로 부르거나 내부 API를 만들 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 7 · 22, 노션 기획안 「REST 호출」

## 언제 REST, 언제 이벤트

| 방식 | 쓰는 때 | 예 |
|---|---|---|
| REST (동기) | 결과를 받아야 다음 단계로 갈 수 있을 때 | 상품 예약 · 잔액 차감 · 정산금 지급 |
| Kafka 이벤트 (비동기) | 일어난 일을 알리고, 받는 쪽이 나중에 처리해도 될 때 | 구매 확정 → 정산 · 알림, 회원 변경 → 복제본 |
| 복제본 | 다른 서비스 데이터를 자주 읽어야 할 때 | 회원 닉네임 · 배송지 · 계좌 → `MEMBER_REPLICA` |

전체 호출 목록 → [내부 API 목록](../reference/internal-apis.md), 이벤트 → [Kafka 토픽 표](../reference/kafka-topics.md)

## 경로와 보호

| 규칙 | 내용 |
|---|---|
| 경로 | `/internal/v1/...` (외부 API 규칙과 같은 명명) |
| Gateway | 라우팅하지 않음 (외부에서 부르면 404) |
| 포트 | 서비스 포트는 외부에 열지 않음 → [포트](../reference/ports.md) |
| 인증 | `X-Internal-Key` 헤더 필수. 사용자 요청에서 이어진 호출이면 사용자 토큰도 그대로 전달 |

## 클라이언트

`RestClient` + HTTP Interface(`@HttpExchange`). OpenFeign · 디스커버리 서버는 쓰지 않습니다. 주소는 Docker 서비스 이름(`http://payment-service:8080`), 서버가 다르면 사설 IP 환경변수입니다. 공통 설정(타임아웃, `Idempotency-Key` · `X-Internal-Key` 자동 추가)은 `common-web`에 있습니다.

## 멱등 키

돈 · 상태를 바꾸는 내부 API는 `Idempotency-Key` 헤더에 **업무 ID**를 넣습니다. 같은 키로 다시 오면 처음 결과를 그대로 돌려줍니다.

| 호출 | 멱등 키 |
|---|---|
| 결제 · 상품 예약 · 해제 · 판매완료 | `orderId` |
| 상품 환불 | `orderItemId` |
| 배송비 환불 | `sellerOrderId` |
| 정산금 지급 | `settlementId` |
| 재판매 | `productId` |

## 에러와 재시도

| 상황 | 처리 |
|---|---|
| 에러 응답 | 내부 API도 `ApiResponse` 형식 |
| 4xx | 재시도하지 않음 |
| 연결 실패 · 503 | 최대 3회 재시도 (0.2 → 0.4초) |
| 응답 타임아웃 | 요청 안에서 재시도하지 않음 → 결과 불명으로 두고 스케줄러가 같은 멱등 키로 확인 |

타임아웃 값 → [장애 처리 값](../conventions/resilience.md)
