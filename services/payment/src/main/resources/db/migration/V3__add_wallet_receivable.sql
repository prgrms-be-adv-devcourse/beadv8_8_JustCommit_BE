-- payment 서비스 V3: 판매자 미수금 칸 (#379)
-- receivable: 마이너스 정산을 판매자 잔액에서 회수하지 못한 금액. 0 이상
-- receivable_after: 거래 처리 후 지갑의 미수금. 어느 정산으로 미수금이 늘었는지 원장에서 추적한다
-- 기존 지갑 · 거래 행은 미수금 0으로 채운다

alter table wallet add column receivable numeric(19, 0) not null default 0;
alter table wallet add constraint ck_wallet_receivable_non_negative check (receivable >= 0);

alter table wallet_transaction add column receivable_after numeric(19, 0) not null default 0;
