# 0012. 사진 검수는 OpenAI Moderation + Claude Haiku

> 상태: ✅ 확정 · 결정일 2026-11-06 · 결정한 사람: 상품 · AI 담당
> 대체: 0009 · 대체됨: -

## 배경

ADR 0009는 Rekognition(유해) + Plant.id(품종)였습니다. Rekognition은 AWS 허용 목록 밖이고, Plant.id는 크레딧 · 판매 형태 판별이 부족했습니다.

## 결정

유해 검사는 OpenAI Moderation(`omni-moderation-latest`, 사진 · 글), 식물 식별은 Claude Haiku(`claude-haiku-5-5`)가 식물 여부 · 판매 형태 · 품종 후보 3개를 한 번에 줍니다. 금지 품종은 `banned_species`로 따로 관리하고, 확신도 0.3 · 0.7 기준으로 반려 · 관리자 확인을 나눕니다.

## 고른 이유

AWS 승인이 필요 없고, 판매 형태(씨앗 · 삽수)와 금지 품종을 한 번에 판단할 수 있습니다. 3차 실험에서 닮은꼴 허용 품종을 잘못 막지 않았습니다.

## 다른 안

| 안 | 버린 이유 |
|---|---|
| Rekognition + Plant.id (0009) | AWS 승인 · 크레딧 · 판매 형태 판별 없음 |
| 로컬 LLM | 메모리 부족 |

## 영향

[사진 검수](../flows/image-review.md), [product](../services/product.md)
