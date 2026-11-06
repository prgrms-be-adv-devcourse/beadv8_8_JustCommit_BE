# 문서 변경 기록

> 언제 읽나: 작업을 시작하기 전, 최근에 바뀐 설계를 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: 모든 docs

새 항목을 맨 위에 추가합니다. 형식: `날짜 · 문서 · 바뀐 내용 (#PR)`

## 기록

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
