-- settlement 서비스 V4: 판매자 부담금 저장 (#433)
-- 반품이 섞인 판매자 주문은 정산금 = 판매액 - 수수료 - 판매자 부담금 (0원 · 음수 허용)
-- V4 이전 정산은 부담금 없이 「판매액 - 수수료」로 계산했으므로 기존 행은 0

alter table settlement add column seller_burden_amount numeric(19, 0) not null default 0;
alter table settlement alter column seller_burden_amount drop default;
