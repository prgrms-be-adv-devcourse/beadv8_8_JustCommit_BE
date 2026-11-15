-- 주문 측정용 판매 중 상품 추가 (#351)
-- 주문 1건이 상품 1개를 판매 완료로 바꾸므로, 주문 측정 전에 새 상품을 넉넉히 넣는다.
-- seed.sql이 먼저 들어가 있어야 한다 (perf 판매자 · 품종 사용).
-- 실행: psql ... -v products=30000 -v tag=order1 < perf/data/more-products.sql
--   tag: 이번에 넣은 상품을 구분하는 이름 (제목 끝에 붙음). k6에는 출력된 id 범위를 넘긴다.
\set ON_ERROR_STOP on
BEGIN;

CREATE TEMP TABLE perf_seller ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY member_id) AS n, member_id AS id FROM seller s JOIN member m ON m.id = s.member_id
WHERE m.email LIKE 'perf-seller-%@perf.test';
CREATE TEMP TABLE perf_species ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id, name FROM species WHERE NOT is_banned;

DO $$ BEGIN
  IF (SELECT count(*) FROM perf_seller) = 0 THEN RAISE EXCEPTION 'perf 판매자 없음: seed.sql 먼저'; END IF;
END $$;

CREATE TEMP TABLE perf_new ON COMMIT DROP AS
WITH ins AS (
  INSERT INTO product (created_at, updated_at, description, grow_note, height_cm, is_hidden, pot_size, price,
                       seller_id, status, title, species_id)
  SELECT now(), now(), sp.name || ' 부하 테스트 상품입니다.', NULL, 10 + g % 50, false, 'M',
         (1 + g % 10) * 10000, s.id, 'ACTIVE', sp.name || ' ' || :'tag' || '-' || g, sp.id
  FROM generate_series(1, :products) g
  JOIN perf_seller s ON s.n = 1 + g % (SELECT count(*) FROM perf_seller)
  JOIN perf_species sp ON sp.n = 1 + g % (SELECT count(*) FROM perf_species)
  ORDER BY g
  RETURNING id
)
SELECT id FROM ins;

INSERT INTO product_picture (s3_url, sort_order, product_id)
SELECT 'https://example.com/perf/' || id || '.jpg', 0, id FROM perf_new;

-- id가 빈틈없이 이어졌는지 확인 (k6가 범위로 고른다)
SELECT min(id) AS first_id, max(id) AS last_id, count(*) AS inserted,
       (max(id) - min(id) + 1 = count(*)) AS contiguous
FROM perf_new;

COMMIT;
