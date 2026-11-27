-- 정산 대상 조회 내부 API (#657): confirmed_at 기간 + (confirmed_at, id) 복합 커서 조회용 인덱스.
-- 조회의 정렬과 커서가 이 인덱스 순서와 같아, 페이지마다 커서 위치부터 limit개만 읽고 정렬하지 않는다.
-- 100만 행에서 size 100으로 끝까지 읽을 때 SQL 시간(VACUUM 후, 캐시가 찬 같은 조건): 2일 11.9ms, 7일 44.1ms, 30일 177.3ms.
-- sellerOrderId만 커서로 쓰면 첫 페이지가 pk를 처음부터 훑어 120~270ms가 걸리고 전체 51.7ms, 184.3ms, 386.7ms였다
create index idx_seller_order_confirmed_at_id on seller_order (confirmed_at, id);
