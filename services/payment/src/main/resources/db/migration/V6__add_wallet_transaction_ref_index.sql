-- payment 서비스 V6: 정산 원장 조회 인덱스 (#393)
-- 정산 대사의 원장 조회(POST /internal/v1/settlementPayouts/ledgers)는 예상 밖 지갑의 행도 찾으려고 wallet_id 없이
-- ref_type, ref_id로만 조회한다. 그래서 uk_wallet_transaction_ref(wallet_id, ref_type, ref_id)를 쓰지 못하고
-- 배치마다 테이블 전체를 읽는다. 정산 ID 100개로 바로 좁히도록 ref_id를 앞에 둔다.
-- 100만 행 측정: 조회 p95 95ms → 14ms, 조회당 읽는 페이지 약 14,600 → 약 730, 원장 쓰기는 행당 약 1.5µs 증가
create index idx_wallet_transaction_ref_id_ref_type on wallet_transaction (ref_id, ref_type);
