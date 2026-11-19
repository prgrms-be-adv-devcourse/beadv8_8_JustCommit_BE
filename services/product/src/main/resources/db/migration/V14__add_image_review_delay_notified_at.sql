-- product 서비스 V14: 관리자 확인 12시간 지연 알림 (#509, docs/flows/image-review.md 「관리자 수동 검수」)
-- 관리자 확인으로 간 지 12시간이 지난 검수를 묶어 ImageReviewDelayed 이벤트 1개로 보내고, 보낸 건에 시각을 채운다
-- 이미 채운 건은 다시 모으지 않아 같은 건을 두 번 알리지 않음. 기존 행은 NULL(아직 안 알림)로 시작
alter table image_review add column delay_notified_at timestamp(6);
