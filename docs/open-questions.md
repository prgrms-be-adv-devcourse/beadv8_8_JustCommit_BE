# 미정 항목

> 언제 읽나: 아직 정하지 않은 것이 무엇이고 누가 정하는지 확인할 때
> 상태: ⬜ 미정 · 마지막 수정 2026-11-06 · 관련: Miro 18, 노션 기획안 맨 위 「남은 미정」

## 미정 목록

| 항목 | 지금 안 · 어긋난 곳 | 담당 | 기한 |
|---|---|---|---|
| 소비 실패 재시도 방식 | 블로킹(`원래토픽.DLT`) vs 논블로킹(`-dlt`), 🟨 회원 복제본만 블로킹 → [이벤트 규칙](conventions/events.md#실패-처리) | 공통 (messaging) | Sprint 1 |
| 결제 중 주문 행 잠금 (Q41) | A 잠금 쥔 채 REST vs B `PAYING` 상태 | 주문 · 결제 담당 | 회신 대기 |
| 계좌 변경 확인 호출 | 노션 1.3은 "계좌 변경 확인: payment · market 병렬", 2.3은 "다른 서비스 확인 안 함" | member | – |
| 관리자 확인 기한 | 노션 3.4는 12시간 알림 + 4시간 뒤 자동 반려(16시간), 반려 사유 표와 3.16은 "24시간" | 상품 | – |
| 사진 최대 장수 | 노션 3.2는 10장 (11/5 회의에서는 5장) | 상품 | – |
| Gateway 실패 응답 코드 | 🟨 503 / 504 / 502 / 500 | 공통 (김환희) | 회신 대기 |
| Saga 결제 보상 목록 | 결제 흐름만 정리 | 성수 | Sprint 1 |
| 이벤트별 payload 필드 · 수락 관련 이벤트 이름 · 반품 이벤트 `Return*` 이름 | 🟨 `ReturnRequested` · `ReturnApproved` · `ReturnRejected` (환불 = 돈, 반품 = 택배로 나눔) | 각 발행 서비스 (#227) | Sprint 1 |
| `ShipmentPickedUp` 유지 여부 | 구독자 없음 | market | Sprint 1 |
| 내부 API 경로 | 계좌 조회 · 탈퇴 확인 3개, 🟨 제안 행 | 각 담당 | 서비스 분리 때 |
| 요청 수 제한 값 | 카카오 로그인 · 중복 확인 · 초과 응답 | Gateway | 시간이 남으면 |
| 탈퇴 재확인 · 소셜 연결 해제 회원 재로그인 | – | member | – |
| 메모리 실측 · 모니터링 위치 | 약 7GB / 8GB, 넘치면 데이터 서버로 | 인프라 | Sprint 1 |
| DB 분리 순서 · Flyway 규칙 | – | rlaksl (#253) | Sprint 1 |
| JWT 키 교체 절차 | `kid` + 옛 키를 Access 수명만큼 유지 (제안) | 인프라 | – |
| 회원별 Refresh Set · 인증 메일 키 이름 | 🟨 `auth:email:daily` · `auth:email:attempts` | member | Sprint 1 |
| DLT 재처리 도구 | 🟨 `failed_event` 표 + 재처리 내부 API | 공통 (messaging) | Sprint 2 |
| `outbox_event` 나머지 칸 | [이벤트 규칙](conventions/events.md#outbox) | 공통 (messaging) | Sprint 1 |
| 외부 AI 약관 (학습 사용 여부) | OpenAI · Anthropic | 김환희 | – |
| `ProductApproved` 추천용 필드 | – | 성수 | Sprint 2 |
| 챗봇 범위 | 보류 (AI 담당 부재) | AI | – |
| ai-service · Gateway NodePort | – | 인프라 | Sprint 2 |
| 탈퇴 잔액 정리 중 지갑 잠금 방식 | – | 결제 | – |
| API 경로 개선 시점 | 서비스 분리 때 프론트와 함께 | 팀 | – |
| 하루 1번 스케줄러 실행 시각 | 요금표 버전 대사 · 토스 대사 | 인프라 · 각 담당 | – |
| Grafana 접속 제한 방법 | – | 인프라 | – |

정해지면 이 표에서 행을 지우고, 결정이면 `decisions/`에 ADR을 추가하고, 해당 문서를 고칩니다.
