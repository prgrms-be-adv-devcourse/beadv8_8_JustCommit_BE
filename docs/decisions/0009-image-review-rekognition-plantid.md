# 0009. 사진 검수는 Rekognition + Plant.id + 금칙어

> 상태: ✅ 확정 · 결정일 2026-11-03 · 결정한 사람: AI 담당 · 팀
> 대체: - · 대체됨: -

## 배경

상품 사진의 유해성 검사와 품종 판별이 필요합니다. 처음 안은 Vision LLM이었습니다.

## 결정

`product.review.v1`으로 비동기 검수: Rekognition(유해 사진) → Plant.id(품종) + `BANNED_KEYWORD`. 결과는 `ACTIVE`(`ProductApproved`) / `REJECTED`(`ProductRejected`) / 관리자 수동 검수입니다.

## 고른 이유

Vision LLM은 메모리 · 응답 시간이 부담이고, 전용 API가 정확하고 가볍습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| Vision LLM | 메모리 · 응답 시간 |
| 동기 검수 | 등록 응답이 외부 API 시간만큼 느려짐 |

## 영향

[사진 검수 흐름](../flows/image-review.md), [product](../services/product.md). ⬜ Rekognition은 AWS 사용 승인이 필요합니다.
