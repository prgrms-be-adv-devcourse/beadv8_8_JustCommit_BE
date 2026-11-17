-- 상품 버전 (#444, ADR 0021)
-- 상품 행을 바꾸는 모든 UPDATE에서 +1. product.events.v1 payload의 productVersion이 되고(#456),
-- 받는 쪽(ai 상품 스냅샷)은 저장된 값보다 클 때만 반영한다. 기존 행은 0에서 시작
alter table product add column version bigint not null default 0;
