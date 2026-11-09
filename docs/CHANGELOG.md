# 문서 변경 기록

> 언제 읽나: 작업을 시작하기 전, 최근에 바뀐 설계를 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-09 · 관련: 모든 docs

새 항목을 맨 위에 추가합니다. 형식: `날짜 · 문서 · 바뀐 내용 (#PR)`

## 기록

- 2026-11-09 · reference/kafka-topics.md · glossary.md · services/market.md · open-questions.md · 반품 이벤트 이름 확정 `ReturnRequested` · `ReturnApproved` · `ReturnRejected` · `OrderItemReturned`(`OrderItemRefunded` 대체), 미정 목록에서 이벤트 이름 항목 정리 (#242)
- 2026-11-09 · reference/kafka-topics.md · glossary.md · services/market.md · 판매자 전부 거절을 `SellerOrderRejected`(`reason`: `SELLER` · `AUTO`)로 분리, `SellerOrderCancelled`는 구매자 취소만 (#242)
- 2026-11-09 · reference/kafka-topics.md · conventions/events.md · services/market · notification · settlement · `order.events.v1` · `shipment.events.v1`을 `market.events.v1` 하나로 합침(토픽 13→12개, ADR 0018), 판매자 수락 `SellerOrderAccepted`(`rejectedItemCount`) · `SellerOrderCancelled` `reason`(`BUYER` · `SELLER` · `AUTO`)별 알림 확정 (#242)
- 2026-11-09 · glossary.md · 「수락 · 거절」 코드에 `SellerOrderAccepted` 추가 (#242)
- 2026-11-09 · reference/internal-apis.md · 상품 예약 요청 형식(`items[{productId, expectedPrice}]`, `Idempotency-Key` 확인)과 409 코드 `PRODUCT_NOT_RESERVABLE` · `RESERVATION_RELEASED` 명시 (#236)
- 2026-11-08 · testing/scenarios.md · 노션 「테스트 시나리오」 4절에 맞춤: S-C03 · D01 · D03 · D07 · E05 · F01 · H03 문장 수정, S-C08 · C09 · D10 추가, P 그룹 분리, 상세 칸은 노션 링크 (#259)
- 2026-11-08 · testing/strategy.md · WireMock 규칙(스텁 위치 · 주소 바꿔 끼우기 · 응답 흉내 7가지 · verify) 추가, ID 그룹에 P (#259)
- 2026-11-08 · services/gateway · notification · settlement · 테스트 시나리오 ID 목록을 노션 서비스 칸에 맞춤 (#259)
- 2026-11-08 · services/member.md · conventions/auth.md · flows/signup-and-replica.md · 계좌 변경 때 다른 서비스 확인 없음 확정, 아이디 찾기(이메일) · 판매자 상점 소개글, JWT 키 교체는 범위 밖(전원 재로그인), 탈퇴 막는 조건에 수락 대기 주문 (#259)
- 2026-11-08 · services/payment.md · 충전 취소를 여러 번 부분 취소로(남은 취소 가능 금액 · 진행 중 1건 · 결과 불명 조회 3회), `ALREADY_PAID` · `ALREADY_REFUNDED`, 대사에서 잔액 줄이는 불일치 처리는 ⬜ (노션에서 문장 삭제) (#259)
- 2026-11-08 · flows/image-review.md · 관리자 확인 기한 12시간 알림 + 12시간 뒤 `REVIEW_EXPIRED`(24시간), 이의 신청 409 코드 (#259)
- 2026-11-08 · reference/ports.md · infra/observability.md · infra/aws.md · Gateway NodePort 30080 · market 30083 · ai NodePort 없음, Grafana SSH 터널, 로그 금지 항목 · 메시지 규칙, S3 업로드 · 경로 · 수명 주기 (#259)
- 2026-11-08 · open-questions.md · 정해진 8건 삭제(소비 실패 방식 · 계좌 변경 확인 · 관리자 확인 기한 · 사진 장수 · `ShipmentPickedUp` · JWT 키 교체 · NodePort · Grafana), 새 미정 5건 추가 (#259)
- 2026-11-08 · decisions/0016 · 0017 · 상품 판매 완료 · 예약 해제 · 판매 재개를 market 이벤트로(0003 대체), 소비 실패는 제자리 재시도 후 `원래토픽.DLT` (#259)
- 2026-11-08 · reference/internal-apis.md · product REST는 예약 하나만 남김 (`expectedPrice`, 409 `PRICE_CHANGED`, `RELEASED` orderId 409), 결제 `ALREADY_PAID` · 환불 `ALREADY_REFUNDED` (#259)
- 2026-11-08 · reference/kafka-topics.md · `market.order-paid` · `order-expired` · `order-failed` · `order-item-cancelled` · `order-item-returned` (`.v1`, 키 orderId) 추가해 토픽 13개, `ShipmentPickedUp` 만들지 않음 (#259)
- 2026-11-08 · services/market.md · services/product.md · 판매 완료 · 해제 이벤트, `PRODUCT_RESERVATION`, `PAYMENT_WINDOW_EXPIRED`, 결과 불명 복구, 택배 웹훅 · 모의 택배사 상세, 범위 밖 항목 (#259)
- 2026-11-08 · flows/order-payment.md · flows/cancel-refund.md · 예약 타임아웃은 주문 실패 + `order-failed`, 결제 기한 직접 확인, 결과 불명 결제 · 환불은 Saga 복구(5회 → `REVIEW_REQUIRED`), 판매 재개는 환불 성공 뒤 이벤트 (#259)
- 2026-11-08 · reference/schedulers.md · 상품 해제 · 판매 완료 재시도 작업 삭제, 결제 대기 만료는 뒷정리만, 관리자 확인 기한 12 + 12시간, 요금표 대사 09:30 (#259)
- 2026-11-08 · conventions/events.md · internal-api.md · resilience.md · common-modules.md · `market.<이름>.v1` 토픽 규칙, 소비 실패 방식 확정, 멱등 키 표 정리, 예약 · 환불 타임아웃 처리 (#259)
- 2026-11-08 · glossary.md · reference/env-vars.md · 결제 기한 만료 · 취소 중 용어 추가, 예약 해제 · 판매 재개 코드 이름, DLT 이름 통일, 토픽 환경변수 13개 (#259)
- 2026-11-06 · infra/database.md · #253 팀 확인 규칙 반영 (`{service}_db` · `{service}_app`, PUBLIC CONNECT 회수, 분리 순서, 초기 데이터, Flyway 파일 · 번호 충돌 규칙), 상태 ✅
- 2026-11-06 · AGENTS.md · 「이슈 · 진행 상황」 절 추가 (Feature · Task 읽는 순서, To-do 체크와 근거 줄, 이슈 수정 전 확인). Task 템플릿 To-do에 근거 줄 안내
- 2026-11-06 · reference/schedulers.md · 업무 기한은 market 정책 링크로, 표준 용어(주문상품 · 판매 완료 · 택배사), 재시도 간격 · 종류 칸 뜻 명시, Outbox 대상 서비스 명시, Gateway 스케줄러 없음, 하루 1번 실행 시각 ⬜
- 2026-11-06 · glossary · kafka-topics · market · notification · open-questions · 환불(돈)과 반품(물건 · 택배)을 나눔. 반품 이벤트 이름을 🟨 `ReturnRequested` · `ReturnApproved` · `ReturnRejected`로 제안, 돈 이벤트 `OrderItemRefunded`는 그대로
- 2026-11-06 · glossary.md · 유비쿼터스 언어 사전으로 개편 (표준 용어 · 코드 이름 · 별칭 · 원본, 이름 통일 기준 8건 🟨). AGENTS.md에 「표준 용어만 쓴다」 규칙 추가
- 2026-11-06 · services/market.md · 조각마다 서비스 이름 명시, 시간 값 기준 시점(결제 대기 = 주문 생성 시각부터 10분 등), 표준 용어로 통일(수락 · 주문상품 · 판매 완료 · 판매 재개), 「하지 않는 것」 행 추가, 스케줄러 주기는 스케줄러 표로 링크
- 2026-11-06 · 전체 · 노션 「정책 정리」(11/6) 반영: 서킷 브레이커 · Resilience4j 제거, 재시도 연결 실패 1회, HTTP Service Client 그룹, 판매자 수락 후 배송, `BOOKED` 취소 택배사 먼저 · `CANCEL_PENDING`, 반품 7절 · 음수 정산 · 미수금, 정산 건별 지급, 배송비 매출 이동 시점, 지역 구분 배송비, 충전 취소 · 인출, 회원 토픽 2개(토픽 8개) · 계좌 내부 API, 사진 검수 Moderation + Haiku · 이의 신청 · 금지 품종 표, 품종 215종, 상품 의미 검색 범위 밖, Gateway · market K3s 2파드, 스케줄러 SKIP LOCKED · ShedLock. 소비 실패 재시도 방식은 ⬜. ADR 0011 ~ 0015 추가 (0004 · 0009 대체)
- 2026-11-04 · 나머지 문서 전부 · conventions · reference · flows · infra · testing · overview · glossary · open-questions 채움, ADR 0001~0010 추가 (노션 기획안 · Miro · 코드 기준). 시퀀스 다이어그램은 경로를 `/api/v1`로, 추천 흐름을 품종 단위로 맞춤
- 2026-11-04 · services/ 8개 · 하는 일 · 소유 테이블 · API · 이벤트 · 스케줄러 · 테스트 ID 채움 (노션 「MSA 분리 지점」 · 「DB 분리」 · 「Kafka 이벤트」, Miro 5 · 22, API 명세서 기준). gateway · notification · ai 정책 추가
- 2026-11-04 · 전체 · docs 구조 새로 만듦. 세미 문서는 `legacy/`로 이동, `policy.md`는 `services/*.md`의 「정책」으로 나눔 (탈퇴 · 환불 상품 재판매 · Refresh 1일 · 로그아웃 블랙리스트는 11/2 · 11/3 결정으로 바꿈)
