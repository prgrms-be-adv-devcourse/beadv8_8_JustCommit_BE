# 용어집 (유비쿼터스 언어)

> 언제 읽나: 같은 대상을 부르는 표준 이름을 찾을 때, 별칭(다른 이름 · 일상어 · 영문)으로 검색할 때, 코드 이름과 문서 이름을 맞출 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-10 · 관련: 노션 「유비쿼터스 언어 정리」, 전체

문서 · 코드 · PR · 회의에서는 「표준 용어」만 씁니다. 「별칭」은 검색용이며 본문에 쓰지 않습니다. 시간 · 금액 · 횟수 같은 값은 여기 적지 않고 「원본」 문서를 봅니다.

## 주문 · 배송

주문 · 배송 · 취소 · 반품 용어입니다 (market 서비스).

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| 주문 | `Orders` | 구매, 주문서 | 구매자가 한 번에 결제한 묶음. 여러 판매자의 상품이 섞일 수 있음 | [market](services/market.md) |
| 판매자 주문 | `SellerOrder` | 판매 주문, 셀러 주문 | 주문을 판매자별로 나눈 단위. 배송 · 배송비 · 정산 · 리뷰 · Kafka Key의 기준 | [market](services/market.md) |
| 주문상품 | `OrdersItem` | 주문 상품, 주문 아이템 | 주문 안의 상품 하나. 취소 · 반품 · 구매 확정의 단위 | [market](services/market.md) |
| 결제 대기 | `PAYMENT_PENDING` (주문) | 결제중, 미결제 | 잔액이 부족해 결제를 기다리는 주문 | [market 정책](services/market.md#정책) |
| 결제 기한 만료 | `PAYMENT_WINDOW_EXPIRED` | 결제 시간 초과 | 결제 대기 기한이 지나 결제를 거절하는 것. 결제할 때 market이 직접 확인 | [market 정책](services/market.md#정책) |
| 수락 · 거절 | `ACCEPT_PENDING` · `SellerOrderAccepted` · `SellerOrderRejected` | 주문 확인, 주문 승인 | 결제 후 판매자가 주문을 받을지 정하는 단계. 수락해야 배송이 생김 | [market 정책](services/market.md#정책) |
| 자동 거절 | – | 자동 취소 (구매자 알림 문구) | 판매자가 기한 안에 응답하지 않아 거절된 주문 | [market 정책](services/market.md#정책) |
| 배송 | `Shipment` | 택배, 배송 건 | 판매자 주문 하나의 택배. 반품은 방향이 다른 새 배송 | [market](services/market.md) |
| 택배 예약 | `BOOKED` | 접수, 택배 접수, 픽업 대기 (화면 표시) | 택배사에 수거를 예약한 상태. 상품 「예약」과 구분해 항상 「택배 예약」으로 씀 | [market 정책](services/market.md#정책) |
| 수거 | `PICKED_UP` | 픽업, 가져감, 집하 | 택배사가 상품을 가져간 상태. 이후에는 취소 대신 반품 | [market 정책](services/market.md#정책) |
| 배송 완료 | `DELIVERED` | 도착 | 구매자에게 도착한 상태. 자동 구매 확정 · 반품 기간의 기준 시점 | [market 정책](services/market.md#정책) |
| 취소 대기 | `CANCEL_PENDING` (배송) | – | 택배사 취소 결과를 기다리는 배송 상태 | [취소 · 환불](flows/cancel-refund.md) |
| 택배사 | – | 404-express, 모의 택배사, courier | 배송을 맡는 외부 서비스 (지금은 모의 택배사 404-express) | [market](services/market.md) |
| 웹훅 | – | webhook, 콜백 | 택배사가 배송 상태 · 취소 · 요금표 변경을 알려 주는 HTTP 요청 | [market 정책](services/market.md#정책) |
| 배송비 요금표 | ⬜ 테이블 이름 미정 | 운임표 | 지역 구분(기본 + 제주 · 도서산간) 배송비 표. market이 버전과 함께 보관 | [market 정책](services/market.md#정책) |
| 구매 확정 | `PurchaseConfirmed` · `OrderItemAutoConfirmed` | 구매 완료, 거래 완료 | 구매자가 받았다고 확정 (직접 또는 자동). 정산의 시작점 | [구매 확정 → 정산](flows/confirm-settlement.md) |
| 취소 | `OrderItemCancelled` · `SellerOrderCancelled` | 주문 취소 | 수거 전에 구매자가 주문상품을 취소하는 것 | [취소 · 환불](flows/cancel-refund.md) |
| 취소 중 | ⬜ 상태 이름 미정 (주문상품) | 환불 확인 중 | 환불 결과를 모르는 주문상품. 환불이 확정되면 취소 완료 | [취소 · 환불](flows/cancel-refund.md#실패와-재시도) |
| 환불 | `refunds/*` (payment) | 돈 돌려주기, 결제 취소 | **돈**이 주체. 결제한 돈을 구매자에게 돌려주는 것. 원인은 수거 전 취소 · 판매자 거절 · 자동 거절 · 반품 완료 | [payment](services/payment.md#정책) |
| 반품 | `Return*` · `OrderItemReturned` | 환불 요청, 반품 요청, 교환 | **물건(택배)**이 주체. 받은 상품을 판매자에게 택배로 돌려보내는 절차. 끝나면 그 결과로 환불 | [취소 · 환불](flows/cancel-refund.md#반품-환불) |
| 반품 신청 | `ReturnRequested` | 환불 요청 | 배송 완료 후 판매자 귀책으로 상품을 돌려보내겠다는 요청 | [취소 · 환불](flows/cancel-refund.md#반품-환불) |
| 승인 · 거절 (반품) | `ReturnApproved` · `ReturnRejected` | 수락 (반품에는 안 씀) | 판매자가 반품을 받아들이거나 거절. 거절하면 그 주문상품은 바로 구매 확정 | [취소 · 환불](flows/cancel-refund.md#반품-환불) |
| 반품 완료 | `OrderItemReturned` (반품 환불 뒤 발행) | 반품 도착 | 반품 택배가 판매자에게 도착한 것. 이때 환불이 일어남 | [취소 · 환불](flows/cancel-refund.md#반품-환불) |
| 판매자 부담금 | – | 반품비, 왕복 택배비 | 반품 때 판매자가 내는 배송비 | [취소 · 환불](flows/cancel-refund.md#반품-환불) |

## 돈

예치금 · 정산 용어입니다 (payment · settlement 서비스).

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| 예치금 | `WALLET` (회원) | 잔고, 잔액, 포인트, 지갑 | 회원 지갑의 돈 = 충전금 + 인출 가능 금액 | [payment](services/payment.md) |
| 충전금 | `charge_balance` | 충전 잔액 | 토스로 충전한 금액. 계좌로 인출하지 않음 (탈퇴 정리 때만) | [payment 정책](services/payment.md#정책) |
| 인출 가능 금액 | `withdrawable_balance` | 정산금, 출금 가능액 | 판매로 받은 정산금. 계좌로 인출 가능 | [payment 정책](services/payment.md#정책) |
| 충전 취소 | `CHARGE` · `CANCEL_PENDING` (충전) | 충전 환불, 결제 취소 | 충전한 돈을 원래 결제수단으로 돌려받는 것 | [payment 정책](services/payment.md#정책) |
| 인출 | `CASH_OUT` | 출금, 환급 | 인출 가능 금액을 계좌로 보내는 것 | [payment 정책](services/payment.md#정책) |
| 홀딩 지갑 | `HOLDING` | 보관 계좌, 에스크로 | 결제된 돈을 정산 전까지 맡아 두는 시스템 지갑 | [payment](services/payment.md) |
| 매출 지갑 | `REVENUE` | 수익 계좌, 플랫폼 계좌 | 수수료 · 배송비 · 판매자 부담금이 쌓이는 시스템 지갑 | [payment](services/payment.md) |
| 정산 | `SETTLEMENT` | 정산금 지급 | 판매자 주문이 끝나면 판매액에서 수수료 · 부담금을 빼 판매자에게 줄 돈 | [구매 확정 → 정산](flows/confirm-settlement.md) |
| 음수 정산 | – | 조정 정산, 마이너스 정산 | 부담금이 판매액보다 커서 0보다 작은 정산 | [구매 확정 → 정산](flows/confirm-settlement.md) |
| 미수금 | – | 빚, 미납금 | 음수 정산을 회수하지 못한 금액. 남아 있으면 인출 · 탈퇴 불가 | [구매 확정 → 정산](flows/confirm-settlement.md) |
| 결과 불명 | `last_error` | 타임아웃, 알 수 없음 | 응답이 없어 상대가 처리했는지 모르는 상태. 바로 되돌리지 않고 같은 멱등 키로 확인 | [장애 처리](conventions/resilience.md#결과-불명-처리) |
| 확인 필요 | `REVIEW_REQUIRED` | 수동 처리, 사람 확인 (돈 · 주문) | 자동 복구를 포기하고 사람이 확인해야 하는 상태 | [스케줄러](reference/schedulers.md) |

## 상품 · 검수

상품 상태와 사진 검수 용어입니다 (product 서비스).

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| 판매 중 | `ACTIVE` | 판매중, 활성 | 구매할 수 있는 상품 (숨김이 아닐 때) | [product 정책](services/product.md#정책) |
| 거래 중 | `PAYMENT_PENDING` (상품) | 결제중, 예약됨 | 주문에 예약된 상품. 화면에 「거래 중」으로 표시 | [product 정책](services/product.md#정책) |
| 판매 완료 | `SOLD_OUT` · `market.order-paid.v1` | 판매완료, 품절 | 결제가 끝난 상품 | [product 정책](services/product.md#정책) |
| 숨김 | `hidden` | 비공개 | 상태와 별개로, 판매자 본인만 보게 하는 표시 | [product 정책](services/product.md#정책) |
| 예약 · 예약 해제 | `products/reservations` · `PRODUCT_RESERVATION` · `market.order-expired.v1` · `market.order-failed.v1` | 상품 잡기, 상품 풀기 | 주문할 때 상품을 그 주문에 묶는 것 · 결제 전에 만료 · 결제 확정 실패로 푸는 것 | [내부 API](reference/internal-apis.md) |
| 판매 재개 | `releaseItem` (`hidden`) · `market.order-item-cancelled.v1` · `market.order-item-returned.v1` | 해제, 재판매 | 판매 완료 상품을 다시 판매 중으로 돌리는 것 (거절 · 반품이면 숨김) | [product 정책](services/product.md#정책) |
| 검수 | `REVIEW_PENDING` · `IMAGE_REVIEW` | 심사, 이미지 리뷰 | 등록 · 수정한 상품의 사진 · 글을 자동으로 확인하는 것 | [사진 검수](flows/image-review.md) |
| 반려 | `REJECTED` | 검수 실패, 거절 (상품) | 검수에서 떨어진 상품. 사유 코드가 붙음 | [사진 검수](flows/image-review.md) |
| 관리자 확인 | – | 수동 검수, 사람 확인 (검수) | 자동 판정이 애매해 관리자가 통과 · 반려를 정하는 것 | [사진 검수](flows/image-review.md#관리자-수동-검수) |
| 이의 신청 | – | 재심사 요청 | 자동 반려가 틀렸을 때 수정 없이 관리자 확인을 요청하는 것 | [사진 검수](flows/image-review.md) |
| 검수 버전 | `review_version` | – | 수정할 때마다 올라가 오래된 검수 결과를 무시하게 하는 번호 | [사진 검수](flows/image-review.md) |
| 품종 · 별칭 | `SPECIES` · `SPECIES_ALIAS` | 식물 종류, 유통명 | 상품이 속한 식물 종류와 그 다른 이름 | [product](services/product.md) |
| 판매 금지 품종 | `BANNED_SPECIES` | 금지 식물 | 법으로 사고팔 수 없는 품종. 품종 목록과 따로 관리 | [product 정책](services/product.md#정책) |

## 회원 · 인증

회원 · 토큰 용어입니다 (member 서비스 · Gateway).

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| 회원 | `MEMBER` | 사용자, 유저 | 가입한 사람. URL 경로는 `users`, 코드 · 필드는 `member` | [member](services/member.md) |
| 판매자 | `SELLER` | 셀러 | 판매자로 등록한 회원 | [member](services/member.md) |
| 회원 복제본 | `MEMBER_REPLICA` | 레플리카, 회원 사본 | 다른 서비스가 회원 이벤트로 채우는 회원 정보 사본 (계좌는 없음) | [가입과 회원 복제본](flows/signup-and-replica.md) |
| 블랙리스트 | `auth:blacklist` | 로그아웃 토큰, 토큰 차단 | 로그아웃한 Access 토큰을 거절하는 목록 | [인증 규칙](conventions/auth.md#토큰-폐기) |
| 회원 단위 폐기 | `auth:revoked-member` | 전체 로그아웃, 모든 기기 로그아웃 | 탈퇴 · 비밀번호 변경 이전에 발급된 토큰을 모두 거절하는 것 | [인증 규칙](conventions/auth.md#토큰-폐기) |
| 이중 검증 | – | – | Gateway와 각 서비스가 모두 토큰을 검증하는 것 | [인증 규칙](conventions/auth.md) |
| 탈퇴 | `MemberWithdrawn` | 회원 삭제, 계정 삭제 | 소프트 삭제 · 익명화. 다른 서비스에는 「탈퇴한 회원」으로 표시 | [가입과 회원 복제본](flows/signup-and-replica.md#탈퇴) |
| Fail-closed | – | 막힘 처리 | 확인 조회가 실패하면 허용하지 않고 막는 정책 (탈퇴) | [가입과 회원 복제본](flows/signup-and-replica.md#탈퇴) |

## MSA

서비스 사이 통신 · 이벤트 용어입니다.

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| 내부 API | `/internal/v1/**` | 내부 REST, 서비스 간 호출 | 서비스끼리 바로 결과가 필요할 때 부르는 API. 외부에서 못 부름 | [서비스 간 REST 규칙](conventions/internal-api.md) |
| HTTP 클라이언트 그룹 | `@HttpExchange` | RestClient, HTTP Interface | 서비스 간 호출을 만드는 방식. 내부 그룹에만 내부 키 · 재시도가 붙음 | [서비스 간 REST 규칙](conventions/internal-api.md#클라이언트) |
| 멱등 키 | `Idempotency-Key` | 중복 방지 키 | 같은 요청이 두 번 와도 한 번만 처리하게 하는 업무 ID. 호출하는 쪽이 넣음 | [서비스 간 REST 규칙](conventions/internal-api.md#멱등-키) |
| 업무 키 | – | 비즈니스 키 | 받는 쪽이 중복 반영을 막는 유일 값 (예: 정산의 판매자 주문 ID) | [이벤트 규칙](conventions/events.md#소비와-멱등) |
| 이벤트 봉투 | `EventEnvelope` | 메시지 형식, 봉투 | 모든 이벤트가 쓰는 공통 형식 (`eventId` · `eventType` · `payload` 등) | [이벤트 규칙](conventions/events.md#메시지-형식) |
| Outbox | `outbox_event` | 아웃박스 | 업무 데이터와 같은 트랜잭션에 저장한 뒤 발행하는 이벤트 표 | [이벤트 규칙](conventions/events.md#outbox) |
| DLT | `원래토픽.DLT` | DLQ, 데드레터 | 재시도해도 실패한 메시지가 가는 토픽 | [이벤트 규칙](conventions/events.md#실패-처리) |
| Saga | – | 사가 | 여러 서비스에 걸친 작업을 단계별로 하고, 실패하면 앞 단계를 되돌리는 방식 | [주문 · 결제](flows/order-payment.md) |

## 인프라

서버 · 도구 이름입니다.

| 표준 용어 | 코드 이름 | 별칭 (검색용) | 뜻 | 원본 |
|---|---|---|---|---|
| Gateway | Spring Cloud Gateway | 게이트웨이, SCG, API 게이트웨이 | 외부 요청을 받아 인증하고 서비스로 보내는 입구 | [gateway](services/gateway.md) |
| Redis | – | 레디스 | 토큰 · 인증번호 · 요청 수 제한을 담는 저장소 | [Redis 키](reference/redis-keys.md) |
| Kafka | – | 카프카 | 서비스 간 이벤트를 전달하는 메시지 브로커 | [Kafka 토픽 표](reference/kafka-topics.md) |
| OpenSearch | – | 오픈서치, ES, 엘라스틱서치 | 상품 검색 엔진 | [검색 · 추천](flows/search-recommend.md) |
| K3s · 파드 | – | 쿠버네티스, k8s | Gateway · market · ai-service를 띄우는 가벼운 쿠버네티스와 그 실행 단위 | [배포](infra/deploy.md) |
| SKIP LOCKED · ShedLock | – | 스케줄러 중복 방지 | 파드가 여럿일 때 스케줄러가 같은 일을 두 번 하지 않게 하는 방법 | [스케줄러](reference/schedulers.md) |
| 분산 추적 | Tempo · `traceparent` | 트레이싱, 트레이스, span, Jaeger, Zipkin | 요청 하나가 Gateway · 서비스 · DB · 외부 API를 거치며 구간마다 걸린 시간을 이어 기록한 것. traceId로 로그와 연결 | [관측](infra/observability.md) |
| ai-service | – | AI 서버, 추천 서버 | 품종 임베딩 · 추천 · 챗봇을 맡는 서비스 | [ai](services/ai.md) |

## 이름 통일 기준

여러 이름으로 부르던 대상을 아래처럼 정했습니다. 🟨 팀 확인 중이며, 바뀌면 위 표와 함께 고칩니다.

| 대상 | 쓰지 않는 이름 | 표준 용어 | 이유 |
|---|---|---|---|
| 판매자가 주문을 받음 | 승인 | 수락 · 거절 | 반품의 「승인」과 나누기 위해 |
| 판매자가 반품을 받음 | 수락, 환불 승인 | 승인 · 거절 (반품) | 노션 「정책 정리」 7.3 표기 |
| 구매자가 반품을 요청 | 환불 요청 | 반품 신청 | 노션 7절 표기 |
| 돈을 돌려줌 vs 상품을 돌려보냄 | (섞어 씀) | **환불** = 돈 · **반품** = 물건(택배) | 취소해도 환불은 일어남. 반품 이벤트는 `Return*` · `OrderItemReturned` (환불 원인이 취소 · 반품 둘이라 `Refund` 이벤트 이름은 쓰지 않음) |
| 주문 안의 상품 하나 | 주문 상품 | 주문상품 | 노션 표기 |
| `SOLD_OUT` | 판매완료 | 판매 완료 | 노션 상품 상태표 표기 |
| 판매 완료 상품을 다시 팔기 | 해제 | 판매 재개 | 결제 실패 때의 「예약 해제」와 구분 |
| 택배사 예약 | 예약 (단독) | 택배 예약 | 상품 「예약」과 구분 |
| 사진 검수에서 사람이 판단 | 수동 검수, 사람 확인 | 관리자 확인 | 노션 3.3 · 3.4 표기 |
