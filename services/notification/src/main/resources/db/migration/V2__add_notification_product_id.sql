-- 검수 통과 · 반려 알림을 눌렀을 때 이동할 상품 (#561). 주문 알림은 null
alter table notification add column product_id bigint;
