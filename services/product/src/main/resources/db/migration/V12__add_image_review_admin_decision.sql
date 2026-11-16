-- product 서비스 V12: 관리자 검수 확인 · 기한 · 이의 신청 · 재검수 횟수 (#344, docs/flows/image-review.md 「관리자 수동 검수」)
-- 관리자 확인(decision = MANUAL)으로 간 검수는 관리자가 통과 · 반려하거나, 24시간이 지나면 REVIEW_EXPIRED로 자동 반려
-- 이의 신청 · 재검수 횟수 칼럼도 여기서 함께 만든다 (V13은 상품 버전 #444 몫)

-- 최종 판정을 누가 했는지: ENGINE(자동) · ADMIN(관리자) · DEADLINE(24시간 기한 자동 반려). 관리자 확인 대기 중이면 NULL
alter table image_review add column decided_by varchar(20);
alter table image_review add column admin_id bigint;              -- 처리한 관리자 회원 id
alter table image_review add column admin_memo varchar(200);      -- 관리자가 OTHER로 반려할 때 입력한 내용 (판매자에게 보여 줌)
alter table image_review add column decided_at timestamp(6);      -- 최종 판정 시각 (reviewed_at은 엔진 판정 · 관리자 확인 기한의 시작)
alter table image_review add column appeal_memo varchar(200);     -- 이의 신청 메모 (자동 반려에만, 같은 검수 버전 1번)
alter table image_review add column appealed_at timestamp(6);

-- 이미 끝난 자동 판정은 ENGINE으로 채움 (관리자 확인 대기 · 진행 중 · STALE은 그대로 NULL)
update image_review set decided_by = 'ENGINE', decided_at = reviewed_at
where status = 'DONE' and decision in ('APPROVED', 'REJECTED');

-- 관리자 확인 목록(오래된 순) · 24시간 기한 스케줄러
create index idx_image_review_manual_reviewed on image_review (reviewed_at) where decision = 'MANUAL';

-- 재검수 횟수: 반려된 상품을 고쳐 엔진 검수를 다시 받을 때 +1 (2회까지). REVIEW_EXPIRED · 엔진 오류 재처리는 세지 않음
alter table product add column resubmit_count integer not null default 0;
