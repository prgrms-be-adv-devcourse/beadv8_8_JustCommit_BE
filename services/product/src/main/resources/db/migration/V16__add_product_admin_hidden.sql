-- product 서비스 V16: 관리자 강제 숨김 (#623, docs/services/product.md 「상품 정책」)
-- 판매자 숨김(is_hidden)과 따로 둔다: 관리자는 판매자 숨김을 풀 수 없고, 판매자는 관리자 숨김을 풀 수 없음
-- 기존 상품은 숨기지 않은 상태로 시작
alter table product add column is_admin_hidden boolean not null default false;
alter table product add column admin_hidden_reason varchar(200);
alter table product add column admin_hidden_at timestamp(6);
