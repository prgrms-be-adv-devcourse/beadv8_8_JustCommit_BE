# 프로젝트 개요

> 언제 읽나: 처음 레포를 볼 때, 전체 목표 · 범위 · 일정을 확인할 때
> 상태: ✅ 확정 · 마지막 수정 2026-11-04 · 관련: Miro 1~4, 노션 기획안 「1. 프로젝트 개요」

## 목표

| 목표 | 내용 | 완료 기준 |
|---|---|---|
| MSA 전환 | 6개 모듈을 서비스별 배포 · 서비스별 DB로 분리 | 모든 서비스가 독립 실행되고 가입 → 구매 → 배송 → 구매 확정 → 정산이 그대로 통과 |
| 외부 API 호환 | Gateway가 기존 경로로 라우팅 | 프론트 변경 없이 기존 API 동작 (경로 · 응답 형식 동일) |
| 데이터 정합성 | Saga로 보상, Outbox로 이벤트 유실 방지 | 결제 · 환불 · 정산 중 한 서비스가 실패해도 돈 · 주문 상태가 어긋나지 않음 |
| AI 추천 | 장바구니에 담은 품종 기준 "같이 키우기 좋은 식물" | 장바구니 화면에 추천 노출, ai가 죽어도 구매는 정상 |
| 검색 고도화 | OpenSearch + pgvector 의미 검색 | "햇빛 적은 방에서 키울 식물" 같은 문장으로 검색 |
| 안정성 · 운영 | Gateway 인증, 장애 격리, 바뀐 서비스만 배포, 모니터링 · 추적 ID | 서비스 하나를 내려도 나머지가 동작하고 요청 하나를 여러 서비스 로그에서 추적 |

## 범위

| 포함 | 제외 |
|---|---|
| 서비스 분리 · Gateway · Saga · Outbox | 환불 상품 재판매 |
| 품종 기준 AI 추천 · 의미 검색 · 상담 | 이미지 기반 추천 |
| OpenSearch 검색 · 사진 검수(Rekognition · Plant.id) | 출금 · 충전 알림 (`payment.events.v1` 없음) |
| 환불 요청 · 승인 · 반려, 리뷰 | 기존 운영 데이터 이전 |
| 관측(Loki · Prometheus · Grafana), k6 부하 테스트 | 로그용 ELK, 분산 추적 서버(Tempo · Zipkin) |

⬜ 확장 화면(판매자 상점 · 리포트 · 마이페이지 등) 범위는 팀이 정합니다 → [미정 항목](open-questions.md)

## 스프린트 일정

| 스프린트 | 기간 | 목표 |
|---|---|---|
| Sprint 0 | 10/28 ~ 11/3 | 기획 · 설계 |
| Sprint 1 | 11/4 ~ 11/10 | 서비스 분리 · Gateway · 도메인 토픽 · CI/CD |
| Sprint 2 | 11/11 ~ 11/17 | Saga · Outbox · 장애 처리 · 추천 · 검색 · 사진 검수 · K3s · 모니터링 |
| Sprint 3 | 11/18 ~ 11/20 | 장애 · 부하 테스트, 배포 안정화, 발표 준비 |

## 서비스 한눈에

| 서비스 | 한 줄 |
|---|---|
| [gateway](services/gateway.md) | 라우팅 · 1차 인증 · 요청 수 제한 · 장애 격리 |
| [member](services/member.md) | 회원 · 토큰 발급 · 회원 이벤트 |
| [product](services/product.md) | 상품 · 품종 · 검색 · 사진 검수 |
| [market](services/market.md) | 장바구니 · 주문 · 배송 · 환불 · 리뷰, Saga 지휘 |
| [payment](services/payment.md) | 예치금 · 결제 · 환불 · 정산금 지급 |
| [settlement](services/settlement.md) | 정산 |
| [notification](services/notification.md) | 알림 · SSE |
| [ai](services/ai.md) | 품종 임베딩 · 추천 · 의미 검색 · 상담 |

전체 그림은 Miro 4장(서비스 · 데이터 흐름), 15장(서버 배치)에 있습니다.
