# 테스트 시나리오

> 언제 읽나: 시나리오 ID로 내용을 찾거나 결과를 기록할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-20 · 관련: [노션 「테스트 시나리오」 4절](https://www.notion.so/410f93f59cb0808e85ddcfccfaa34bd6)

시나리오마다 어느 서비스의 테스트인지, 무엇을 대신 세우는지(WireMock · 테스트 Kafka · Clock 등), 무엇을 확인하는지는 노션 「테스트 시나리오」 4절 표에 있습니다. 이 문서는 ID · 문장 · 결과 기록용입니다.

## A. 인증 · 보안

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-A01 | 로그인 → member가 RS256 토큰 발급 → Gateway · 서비스 모두 통과 | 정상 | 통합 | PR |  |  |
| S-A02 | 만료 토큰 → Gateway 401 | 예외 | 통합 | PR |  |  |
| S-A03 | 서명 위조 토큰 → Gateway 401, Gateway를 거치지 않고 서비스를 직접 불러도 401 (이중 검증) | 보안 | 통합 | PR |  |  |
| S-A04 | 로그아웃 토큰(블랙리스트) → Gateway 401 | 규칙 | 통합 | PR |  |  |
| S-A05 | 외부에서 `/internal/**` 호출 → 라우팅 없음 (404) | 보안 | 통합 | PR |  |  |
| S-A06 | `X-Internal-Key` 없이 내부 API 직접 호출 → 거부 | 보안 | 통합 | PR |  |  |
| S-A07 | 남의 주문 · 알림 조회 · 수정 → 404 | 규칙 | 통합 | PR |  |  |
| S-A08 | 비밀번호 변경 · 재설정 · 탈퇴 · 소셜 연결 해제 → 폐기 시각 기록 후 `iat <= 끊은 시각`인 Access는 Gateway 401. 옛 인증 버전 Refresh 삭제를 시도하며, 삭제 실패 시에도 인증 버전 비교로 재발급 401 | 보안 | 통합 | PR |  | 검증 (11/19): 직접 탈퇴 · Refresh 폐기(`WithdrawalScenarioTest`, `TokenRevocationServiceIntegrationTest`), Gateway Access 차단(`JwtAuthenticationIntegrationTest`), 소셜 연결 해제 `SOCIAL_UNLINKED` 폐기 · 폐기 후 버전 계약(`TokenRevocationServiceIntegrationTest`, `KakaoUnlinkProcessorIntegrationTest`) |
| S-A09 | 인증번호 5회 오답 시 폐기. 같은 이메일 · 같은 용도의 첫 예약부터 24시간 동안 최대 5회 예약 허용, 한도 초과 시 추가 예약 · 메일 발송 없음(가입 인증은 429, 재설정 · 가입 계정 안내는 기존 200 응답 유지) | 규칙 | 통합 | PR |  |  |
| S-A10 | 탈퇴 재확인: 로컬 비밀번호 오답이 비밀번호 변경과 합산되고, 누적 5번째 오답까지 400, 이후 요청부터 429 `PASSWORD_CHANGE_LOCKED` + `Retry-After`. 비밀번호가 맞아도 탈퇴가 완료되지 않으면 실패 횟수 유지 / 탈퇴 토큰을 다른 회원 · 발급 뒤 인증 버전 변경 · 재사용으로 쓰면 400 `INVALID_WITHDRAWAL_TOKEN` / 공개 인증 경로의 `WITHDRAWAL` 목적은 400 | 보안 | 통합 | PR |  | 완료 (11/18): `WithdrawalServiceIntegrationTest`(비밀번호 변경과 실패 횟수 합산 · 막힌 탈퇴의 횟수 유지 포함), `WithdrawalVerificationIntegrationTest` |
| S-A11 | 폐기 기록이 `PENDING`인 동안 탈퇴 회원의 기존 Access로 내 정보 · 배송지 조회 · 배송지 등록 · 판매자 등록 → 404 `MEMBER_NOT_FOUND`(member 상태 검사), `INACTIVE` 회원의 Refresh 재발급 → 401 `INVALID_REFRESH_TOKEN` | 보안 | 통합 | PR |  | 검증 (11/19): `DELETED` · `INACTIVE` 회원 접근 차단(`WithdrawnMemberAccessIntegrationTest`), `INACTIVE` 재발급 401(`TokenReissueServiceTest`) |

## B. 회원

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-B01 | 회원가입 → `MemberCreated` → product · market · payment · settlement 복제본 생성, 장바구니 · 지갑 생성 | 정상 | E2E | 야간 |  |  |
| S-B02 | 닉네임 변경 → `MemberUpdated` → 각 서비스 복제본 갱신 | 정상 | 통합 | PR |  |  |
| S-B03 | 같은 `MemberCreated` 두 번 → 지갑 · 장바구니 1개만 | 중복 | 통합 | PR |  |  |
| S-B04 | 탈퇴 확인 3곳 병렬 호출, 하나라도 실패하면 탈퇴 막음 (Fail-closed) | 장애 | 통합 | PR |  | 완료 (11/18): `WithdrawalScenarioTest`, `WithdrawalCheckServiceIntegrationTest` |
| S-B05 | 직접 탈퇴(로컬 비밀번호 · 카카오 탈퇴 토큰) → 개인정보 컬럼 NULL · 배송지 삭제 · 탈퇴 기록 저장 · `MemberWithdrawn` Outbox 저장이 한 트랜잭션, 저장이 실패하면 Outbox까지 모두 롤백 | 정상 | 통합 | PR |  | 완료 (11/19): 직접 탈퇴(로컬 · 카카오)에서 익명화 · 배송지 · 탈퇴 기록 · `MemberWithdrawn` Outbox가 함께 커밋되고 저장 실패 시 함께 롤백, 같은 회원의 동시 요청도 이벤트 1건(`WithdrawalServiceIntegrationTest`) |
| S-B06 | 탈퇴 조건 미충족 → 409 `WITHDRAWAL_BLOCKED`, 회원 · 토큰 · 탈퇴 기록 변화 없음. 사전 확인 GET은 차단 사유를 보여 주고, GET이 `ALLOWED`였어도 POST에서 다시 확인 | 규칙 | 통합 | PR |  | 완료 (11/18): `WithdrawalServiceIntegrationTest`(사전 확인 `ALLOWED` 뒤 최종 탈퇴 재확인 포함) |
| S-B07 | 재가입 제한: 탈퇴한 이메일만 · 휴대폰만 · 둘 다 같으면 30일 이내 로컬 · 소셜 가입 거절(일치 항목을 구분하지 않는 같은 응답), 30일이 지나면 가입 가능(정리 스케줄러가 기록을 아직 지우지 않았어도) | 경계 | 통합 | PR |  | 완료 (11/18): `RejoinRestrictionIntegrationTest` |
| S-B08 | 같은 이메일 · 휴대폰으로 가입과 탈퇴가 동시에 진행 → 탈퇴가 커밋되면 가입이 재가입 제한을 우회하지 못함 (실제 PostgreSQL) | 경계 | 통합 | PR |  | 완료 (11/18): 실제 PostgreSQL에서 커밋 전후 · 사전 검사 통과 후 가입 · 탈퇴가 끼어드는 경합(로컬 가입 경로, `RejoinRestrictionIntegrationTest`) |
| S-B09 | 카카오 탈퇴: 연결 해제 실패 → 503 `SOCIAL_UNLINK_FAILED`, 회원 변화 없음 · 탈퇴 토큰 소비됨 / 연결 해제 성공 뒤 DB 롤백 확인 → 회원 `ACTIVE` 유지, 재인증 후 다시 탈퇴할 때 `-101`을 성공으로 처리 / 커밋 결과 불명 → 503 `RESULT_UNKNOWN`, 자동 재요청 금지 | 장애 | 통합 | PR |  | 완료 (11/18): 연결 해제 실패 · 롤백 · 롤백 뒤 재시도(`WithdrawalServiceIntegrationTest`), HTTP 400 + `-101`만 성공(`KakaoAdminClientTest`). `RESULT_UNKNOWN`은 503 응답 매핑 · 쿠키 유지까지 검증(`WithdrawalControllerIntegrationTest`) |
| S-B10 | 카카오 연결 해제 웹훅: 키가 틀리면 401(기록 없음), 맞으면 요청 값과 관계없이 200, 정상 요청은 수신 기록 저장 후 정상 상황에서 3초 안에 응답 / 같은 연결 해제 상태에서 웹훅이 중복 수신돼도 회원 상태 변경 · 토큰 폐기 · 자동 탈퇴를 멱등 처리 / `INCOMPLETE_SIGN_UP` · 회원 없음은 기록만 / `UNLINK_FROM_ADMIN`은 `INACTIVE`만 / 자동 탈퇴 대상은 `ALLOWED`면 탈퇴, `BLOCKED`면 `INACTIVE` + 안내 메일, `UNAVAILABLE`이면 안내 없이 재시도 후 소진 시 `FAILED` | 규칙 | 통합 | PR |  | 검증 (11/19): 수신(`KakaoUnlinkWebhookIntegrationTest`, 저장 실패 · 테이블 잠금 지연에도 3초 안 200), 처리(`KakaoUnlinkProcessorIntegrationTest`), Gateway 라우트(`KakaoUnlinkWebhookRouteIntegrationTest`). 실제 카카오 웹훅은 운영 HTTPS 주소 등록 뒤 확인 |
| S-B11 | 재연결 경쟁: 웹훅으로 `INACTIVE`가 된 뒤 카카오로 다시 로그인 → `ACTIVE` 복구 · 미처리 기록 무효 → 늦게 실행된 자동 탈퇴는 탈퇴하지 않음 / 끊김 조회 직후 `ACTIVE` 상태로 재로그인해도 옛 조회 결과로 `INACTIVE`가 되지 않음 / 폐기 시각과 같은 초에 재연결해 받은 새 Access는 #422에서 재현으로 현재 동작만 확인하고, 해결 · 정상 사용 검증은 #546 | 경계 | 통합 | PR |  | 부분 검증 (11/19): 재로그인 복구 · 무효화 · 경합(3곳 확인 도중 재로그인 포함, `KakaoReloginIntegrationTest`, `KakaoUnlinkProcessorIntegrationTest`), 같은 초 Access 재현(현재 Gateway 규칙상 거절 대상, `KakaoReloginIntegrationTest`). 해결 · 정상 사용 검증은 #546 |

## C. 상품 · 검색

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-C01 | 상품 등록(Presigned 업로드 사진) → 조회 · OpenSearch 검색 노출 (`product.events.v1` → 색인 리스너) | 정상 | 통합 | PR |  |  |
| S-C02 | 상품 숨김 → 검색 · 목록 제외, 진행 중 결제는 유지 | 규칙 | 통합 | PR |  | 검색 · 목록 제외 확인 (11/19): 숨김 이벤트 → 문서 `hidden` → OpenSearch 검색 제외 · 해제 시 다시 노출(`ProductSearchIndexScenarioTest`), 키워드 검색 · LIKE 대체 · 목록 제외(`ProductSearchIntegrationTest` · `ProductSpecificationsTest`). 진행 중 결제 유지는 이 PR에서 확인 안 함 |
| S-C03 | 판매 완료 이벤트(`market.order-paid.v1`)를 두 번 받음 → 1번만 반영, 이미 해제돼 바뀐 상품이 0건이어도 정상 | 중복 | 통합 | PR |  |  |
| S-C04 | 같은 상품을 두 명이 동시 주문 → 한 명만 예약 성공 | 경계 | 통합 | PR |  |  |
| S-C05 | 사진 검수: 유해 → `HARMFUL_IMAGE` / 씨앗 · 삽수 → `PROHIBITED_FORM` / 금지 품종 0.7 이상 → `BANNED_SPECIES` / 확신도 낮음 · 기타 품종 → 관리자 확인 / 통과 → `ACTIVE` · `ProductApproved` → 판매자 통과 알림 | 규칙 | 통합 | PR |  |  |
| S-C06 | 자동 반려 → 이의 신청(같은 버전 1번) → 관리자 통과 / 관리자 반려는 이의 신청 불가 409 | 규칙 | 통합 | PR |  |  |
| S-C07 | 관리자 확인 12시간 지나면 목록에 지연 표시 → 24시간 지나면 기한 스케줄러가 `REVIEW_EXPIRED`로 자동 반려, 등록 제한에 세지 않음 (관리자 알림은 notification 연동 때) | 규칙 | 통합 | PR |  |  |
| S-C08 | 예약 때 주문서 가격(`expectedPrice`)이 하나라도 다름 → 409 `PRICE_CHANGED`, 아무것도 예약 안 함 | 규칙 | 통합 | PR |  |  |
| S-C09 | 해제 이벤트(`market.order-failed.v1`)가 예약보다 먼저 도착 → 늦게 온 예약은 409 (`product_reservation` `RELEASED`) | 경계 | 통합 | PR |  |  |
| S-C10 | 사진 검수 엔진 오류: 외부 API가 재시도 후에도 실패 → `ENGINE_ERROR` → 5분 간격 재처리 성공하면 정상 판정 / 3회 모두 실패하면 관리자 확인(사유 `ENGINE_ERROR`), 재처리는 하루 30회 · 재검수 2회에 세지 않음, 그사이 상품이 수정되면(검수 버전 다름) 재처리 안 함, 30분이 지나도 `REQUESTED`인 검수(멈춘 검수 요청)도 재처리 | 장애 | 통합 | PR |  |  |
| S-C11 | 반려된 상품 수정 → 재검수 1회씩, 2번 다 쓰면 세 번째 수정 409 `RESUBMIT_LIMIT_EXCEEDED` / 이의 신청 중 수정도 1회 / 기한 초과 뒤 수정 · 재요청은 세지 않음 | 규칙 | 통합 | PR |  |  |

## D. 주문 · 결제 Saga

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-D01 | 장바구니 → 주문 → 예치금 차감 → 판매 완료 → 판매자 수락 → 배송 생성 → 알림 | 정상 | E2E | 야간 |  |  |
| S-D02 | 예치금 부족 → `PAYMENT_PENDING` 유지 → 10분 안에 결제가 없으면 만료 · 예약 해제 | 예외 | 통합 | PR |  |  |
| S-D03 | 차감 성공 → 주문 결제 완료와 판매 완료 이벤트(Outbox 행)를 한 트랜잭션에 저장 → 발행이 늦어도 주문은 결제 완료 유지 | 장애 | 통합 | PR |  |  |
| S-D04 | 차감 응답 타임아웃 → 바로 보상하지 않고 같은 멱등 키로 재확인 → 실제 성공이면 진행 | 결과 불명 | 통합 | PR |  |  |
| S-D05 | 결제 처리 중(`PAYMENT_PROCESSING`)으로 멈춘 주문 → 마지막 시도에서 10초 지나면 Saga 복구가 같은 orderId로 결제를 다시 부름 → 결과대로 확정 (결제 완료 · 결제 대기 · 주문 실패) | 장애 | 통합 | PR |  |  |
| S-D06 | 같은 주문 요청 두 번 (같은 `Idempotency-Key`) → 주문 · 차감 1번 | 중복 | 통합 | PR |  |  |
| S-D07 | 내부 호출 4xx · 503 · 응답 타임아웃 → 재시도 안 함 / 연결 실패 → 0.2초 뒤 1번 재시도 | 규칙 | 통합 | PR |  |  |
| S-D08 | 결제 → `ACCEPT_PENDING` → 판매자가 1개 거절 · 나머지 수락 → 거절 상품 환불 · 판매 재개(숨김), 배송 1건 생성 | 정상 | 통합 | PR |  | 완료 (11/19): `SellerOrderDecisionIntegrationTest` (판매 재개 숨김은 product 몫) |
| S-D09 | 판매자 24시간 무응답 → 자동 거절 · 전액 환불 · 구매자에게 「응답이 없어 취소」 | 규칙 | 통합 | PR |  | 일부 완료 (11/19): 자동 거절, 환불, 판매 재개 `AUTO`, 두 파드 동시 실행, 수락과 겹침 `SellerOrderDecisionIntegrationTest`, `OrderSchedulerConcurrencyIntegrationTest`. 구매자 알림은 #288 |
| S-D10 | 결제 기한(10분)이 지난 뒤 결제 → `PAYMENT_WINDOW_EXPIRED` (만료 스케줄러가 아직 안 돌았어도) | 규칙 | 통합 | PR |  |  |
| S-D11 | 결제 시작과 만료가 겹침 → 만료가 먼저 잠그면 결제 시작은 0건이라 결제를 부르지 않음 / 결제 시작이 먼저면 만료가 건너뜀 | 경합 | 통합 | PR |  |  |
| S-D12 | 앞선 시도의 결제 결과가 늦게 도착 → 시도 번호(`attempt_no`)가 달라 반영하지 않음 | 결과 불명 | 통합 | PR |  |  |
| S-D13 | market 파드 2개가 동시에 Saga 복구 → 주문마다 결제를 한 번만 다시 부름 | 중복 | 통합 | PR |  |  |
| S-D14 | 복구 5회까지 결과 불명 → `REVIEW_REQUIRED` · ERROR 로그, 그 뒤 늦은 응답은 반영하지 않음 | 결과 불명 | 통합 | PR |  |  |
| S-D15 | 결제 연결 실패: 첫 호출이면 확정 실패 · `order-failed` / 복구 중이면 결제 처리 중 유지 · `last_error` 기록 | 규칙 | 통합 | PR |  |  |
| S-D16 | 복구 결과 확정 실패 → 주문 실패 · `order-failed` · 장바구니 복원, 복원이 실패하면 함께 롤백하고 다음 주기에 다시 시도 | 장애 | 통합 | PR |  |  |
| S-D17 | 판매자 수락 전 구매자 취소 → 택배사를 부르지 않고 바로 환불, 판매자 주문의 마지막 상품이면 배송비도 환불 | 규칙 | 통합 | PR |  | 완료 (11/19): `SellerOrderDecisionIntegrationTest` |
| S-D18 | 수락, 거절, 구매자 취소가 겹침 → 하나만 반영, 수락 대기가 남지 않았으면 늦게 온 수락, 거절은 409. 수락 뒤 구매자 취소는 기존 취소 대기 흐름 | 경합 | 통합 | PR |  | 완료 (11/19): `SellerOrderDecisionIntegrationTest` |

## E. 취소 · 환불

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-E01 | 수거 전 전체 취소 → 환불 · 택배 예약 취소 · 판매자 알림, 구매자 알림 없음 | 정상 | E2E | 야간 |  | 부분 검증 (11/18): 택배 취소됨 뒤 상품 금액과 배송비 환불, 판매자 전부 취소 알림(`OrderCancelRefundIntegrationTest`). 구매자 화면까지 E2E는 미검증 |
| S-E02 | 상품 3개 중 1개 취소 → 부분 환불, 판매자 알림 | 경계 | 통합 | PR |  | 완료 (11/18): `OrderCancelRefundIntegrationTest` |
| S-E03 | 수거 후 취소 → 거부 | 규칙 | 단위 | PR |  |  |
| S-E04 | 취소 두 번 → 환불 1번, 택배 취소 호출 1번 | 중복 | 통합 | PR |  | 완료 (11/18): `OrderCancelRefundIntegrationTest`(같은 취소 두 번, 복구 두 번) |
| S-E05 | 택배사 취소 실패 → 재시도(10초 → 30초 → 1분)로 취소 완료 | 장애 | 통합 | PR |  |  |
| S-E06 | 반품 신청(배송 완료 72시간 안, 판매자 귀책) → 판매자 알림 · 자동 구매 확정 제외 → 승인 → 반품 배송 → 도착 → 환불 → 구매자 알림 | 정상 | 통합 | PR |  || 부분 검증 (11/20): market의 반품 도착 → 반품 환불 → `RETURNED`, 반품 신청 `COMPLETED`, `OrderItemReturned`(구매자 알림용)와 판매 재개 Outbox 행(`OrderReturnRefundIntegrationTest`). 신청, 승인은 #382, #383 테스트. product 숨김과 알림 수신은 서비스를 이은 검증 전 |
| S-E07 | `BOOKED` 취소 → 택배사 취소 먼저, 응답 없음 → `CANCEL_PENDING` · 환불 안 함 → 취소 큐로 확정 | 결과 불명 | 통합 | PR |  | 부분 검증 (11/18): 택배 결과 전에는 환불 안 함(`OrderCancelRefundIntegrationTest`). 취소 큐 재시도는 `ShipmentCancelQueueIntegrationTest` |
| S-E08 | 택배사 「이미 수거」 → 수거가 요청보다 늦으면 취소 인정 + 반품 자동 접수, 빠르면 거절 · `PICKED_UP` | 경계 | 통합 | PR |  |  |
| S-E09 | 반품 신청 48시간 무응답 → 자동 승인 → 반품 도착 → 환불 · 상품 `ACTIVE` + 숨김 | 규칙 | 통합 | PR |  |  |
| S-E10 | 반품 거절 → 그 상품 바로 구매 확정 | 규칙 | 통합 | PR |  |  |
| S-E11 | 환불 응답 타임아웃 → 주문상품 「취소 중」 유지, 판매 재개 안 함 → 10초 뒤 Saga 복구가 같은 키로 다시 요청 → 환불되면 취소 완료와 판매 재개 1건 | 결과 불명 | 통합 | PR |  | 완료 (11/18): `OrderCancelRefundIntegrationTest` |
| S-E12 | 환불 복구 5번까지 결과 불명 → 주문상품 `REVIEW_REQUIRED`와 ERROR 로그 / 환불 확정 실패(4xx) → 재시도 없이 `REVIEW_REQUIRED`. 둘 다 판매 재개 안 함 | 결과 불명 | 통합 | PR |  | 완료 (11/18): `OrderCancelRefundIntegrationTest` |
| S-E13 | 택배 취소 불가(`CANCEL_LOCKED`) → 환불 없이 주문상품을 정상으로 되돌리고 정산 기준액 재계산 | 규칙 | 통합 | PR |  | 완료 (11/18): `OrderCancelRefundIntegrationTest` |

## F. 배송

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-F01 | 판매자 수락 흐름은 수락된 주문상품만 담은 `READY` 배송만 만들고 택배사를 부르지 않음, 결제만 된 주문에는 배송 없음, 롤백 시 배송 없음 | 규칙 | 통합 | PR |  | 완료 (11/19): `ShipmentCreateIntegrationTest`, `OrderPaymentTransactionsIntegrationTest` |
| S-F02 | 예약 실패 → `READY` 유지 → 택배 예약 큐가 10초 → 30초 → 1분 뒤 같은 멱등 키로 예약 | 장애 | 통합 | PR |  |  |
| S-F03 | 웹훅 중복 · 역순 → 무시하고 200 | 중복 | 통합 | PR |  |  |
| S-F04 | 웹훅 키가 틀림 → 401, 반영 안 함 | 보안 | 통합 | PR |  |  |
| S-F05 | `PICKED_UP` 유실, `IN_TRANSIT` 먼저 → 수거 처리 1번 | 장애 | 통합 | PR |  |  |
| S-F06 | 택배 예약 큐와 취소가 동시에 → `@Version`으로 취소 유지 | 경계 | 통합 | PR |  |  |
| S-F07 | 한 인스턴스가 택배사를 기다리는 동안 다른 인스턴스가 예약 큐를 돎 → 같은 배송 예약은 1번 | 경계 | 통합 | PR |  |  |
| S-F08 | 택배사 응답이 3초 늦어도 결제 쪽 배송 생성은 기다리지 않음 | 장애 | 통합 | PR |  |  |
| S-F09 | 구매자 취소 → 배송 `CANCEL_PENDING` + 누른 시각만 기록, 택배사는 부르지 않음. 같은 취소 동시 2번 → 한 번만 기록 | 경계 | 통합 | PR |  |  |
| S-F10 | 택배 취소 큐: 택배사 취소됨 → `CANCELLED` + 결과 행, 409(누른 시각 2km 안) → `CANCEL_LOCKED` + 결과 행, 반송 → 결과 행 반송 표시 | 규칙 | 통합 | PR |  |  |
| S-F11 | 택배 취소 응답 없음 → `CANCEL_PENDING` 유지 · `last_error`, 10초 → 30초 → 1분 뒤 같은 누른 시각으로 다시 보냄 | 장애 | 통합 | PR |  |  |
| S-F12 | 10분 넘게 남은 `READY` · `CANCEL_PENDING` 수가 `replant_shipment_stuck` 지표로 보임 | 관측 | 통합 | PR |  |  |

## G. 구매 확정 · 정산

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-G01 | 배송 완료 → 구매 확정 → 정산 생성 → 정산금 지급 (settlement → payment REST) | 정상 | E2E | 야간 |  |  |
| S-G02 | 배송 완료 72시간 후 자동 구매 확정 | 규칙 | 통합 | PR |  |  |
| S-G03 | 정산 지급 때 배송비 `HOLDING` → `REVENUE`, 배송비가 환불된 판매자 주문은 건너뜀 | 정상 | 통합 | PR |  | 완료 (11/19): `PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest` |
| S-G04 | 정산금 지급을 같은 settlementId로 두 번 → 1번만 입금 | 중복 | 통합 | PR |  | 완료 (11/19): `PaymentIdempotencyIntegrationTest`, `PaymentSettlementIntegrationTest` |
| S-G05 | 전부 반품 → 음수 정산 → 판매자 잔액에서 회수 → 부족분 미수금 → 다음 입금에서 먼저 차감, 미수금 있으면 인출 불가 | 경계 | 통합 | PR |  | 부분 검증 (11/19): 잔액에서 회수와 부족분 미수금(`PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest`), 다음 입금(충전, 정산금, 환불)에서 먼저 차감(`MemberWalletDepositorTest`, `PaymentServiceSettlementTest`, `PaymentServiceRefundTest`, `ReceivableCollectionIntegrationTest`), 미수금이 있으면 인출 불가(11/20, `CashOutIntegrationTest`) |
| S-G06 | 0원 정산 (판매자 부담금 = 판매액 − 수수료) → 판매자 잔액은 그대로, 판매자 지갑에 `SETTLEMENT` 0원 행 1건, 부담금은 `REVENUE`로 | 경계 | 통합 | PR |  | 완료 (11/19): `PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest` |
| S-G07 | 판매액 0 (전부 반품, 수수료 0) → `HOLDING`에서 판매액을 꺼내지 않고 판매자 잔액에서만 회수, 부족분 미수금 | 경계 | 통합 | PR |  | 완료 (11/19): `PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest` |
| S-G08 | 배송비를 이미 옮긴 판매자 주문의 정산 지급 → 배송비는 다시 옮기지 않고 정산은 지급 | 중복 | 통합 | PR |  | 완료 (11/19): `PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest` |
| S-G09 | 0원 정산과 음수 정산을 같은 settlementId로 다시 보내거나 동시에 보냄 → 회수, 미수금, 배송비 이동이 1번만 | 중복 | 통합 | PR |  | 완료 (11/19): `PaymentServiceSettlementTest`, `PaymentSettlementIntegrationTest` |

## H. 이벤트 신뢰성

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-H01 | Kafka 정지 중 주문 → Outbox 적재 → 복구 후 재전송, 유실 0 | 장애 | 통합 | PR |  |  |
| S-H02 | 같은 이벤트 두 번 소비 → 업무 키로 1번만 반영 (정산 UK, 알림 eventKey) | 중복 | 통합 | PR |  |  |
| S-H03 | 소비 실패 → 1 · 2 · 4초 블로킹 재시도 → `원래토픽.DLT` | 장애 | 통합 | PR |  |  |
| S-H04 | 형식이 깨진 메시지 → 소비자가 멈추지 않고 DLT로 | 예외 | 통합 | PR |  |  |

## I. 알림

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-I01 | 상대가 한 일만 알림 (본인 취소는 본인에게 안 감) | 규칙 | 통합 | PR |  |  |
| S-I02 | SSE 연결 중 알림 저장 → 커밋 후 안 읽은 수 전송, 탭 여러 개 모두 수신 | 정상 | 통합 | PR |  |  |
| S-I03 | Gateway · Nginx를 거친 SSE가 끊기지 않고 30초 ping 유지 | 장애 | E2E | 수동 |  |  |
| S-I04 | 알림 목록 `?read=false` · `true` · 없음 필터, 커서 페이지네이션 | 정상 | 통합 | PR |  |  |

## J. 장애 격리

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-J01 | market 파드 1개 종료 → K3s가 남은 파드로 분배, 주문 계속 성공 | 장애 | E2E | 야간 | 실패한 요청 수, 응답 시간 |  |
| S-J02 | market 파드 모두 종료 → Gateway가 바로 503 + `ApiResponse`, 상품 조회는 정상 | 장애 | E2E | 야간 | 503까지 걸린 시간, 상품 조회 응답 시간 |  |
| S-J03 | 결제 응답 지연 → 연결 1초 · 응답 3초 타임아웃 후 결과 불명 처리 | 장애 | E2E | 야간 | 주문 응답 시간 |  |
| S-J04 | 컨테이너 강제 종료 → 자동 재시작 | 장애 | E2E | 수동 | 재시작 시간 |  |

## K. 관측

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-K01 | traceId 하나로 Gateway → 주문 → 결제 → Kafka → 알림 로그 연결 (Loki) | 관측 | E2E | 수동 |  |  |
| S-K02 | DLT 유입 / Outbox 대기 1분 초과 / 서비스 다운 → Slack 알림 | 관측 | E2E | 수동 |  |  |

## P. 충전

| ID | 시나리오 | 종류 | 수준 | 자동화 | 남길 실측값 | 결과 |
|---|---|---|---|---|---|---|
| S-P02 | 충전 승인 결과 불명 → `PENDING` → 조회로 승인 또는 실패 확정 | 결과 불명 | 통합 | PR |  |  |

S-P01(충전 취소)은 충전 취소를 하지 않기로 해서 지웠습니다. 번호는 다시 쓰지 않습니다.

⬜ ai(추천 fallback) 시나리오는 Sprint 2에 추가합니다.
