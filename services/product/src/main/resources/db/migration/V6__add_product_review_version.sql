-- 사진 검수 (#341, docs/flows/image-review.md)
-- review_version: 등록 0, 수정마다 +1. 검수 결과는 버전이 같을 때만 반영한다 (늦게 온 옛 결과를 무시)
-- 기존 행은 이미 ACTIVE라 0으로 둔다. status 새 값(REVIEW_PENDING · REJECTED)은 CHECK가 없어 고칠 것이 없다 (V1 규칙)
alter table product add column review_version integer not null default 0;