-- 정산 대상 조회 내부 API (#657): confirmed_at 기간 + sellerOrderId 커서 조회용 인덱스.
-- 조회는 이 인덱스만으로 기간, 커서, id 정렬, limit을 처리해 id를 고르고(Index Only Scan), 힙은 고른 행만 읽는다.
-- 100만 행, 최근 2일(약 4,800행, 48페이지)을 커서로 끝까지 읽을 때 (캐시가 찬 같은 조건):
--   인덱스 없음 142ms / 36,227블록, confirmed_at 단일 인덱스 100ms / 190,782블록(페이지마다 기간 전체를 힙에서 읽음),
--   이 인덱스 45ms / 39,550블록. 최근 행이 아직 VACUUM 전이라 visibility map이 비어 있으면 101ms / 216,487블록
create index idx_seller_order_confirmed_at_id on seller_order (confirmed_at, id);
