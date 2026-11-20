-- 상품 검색 측정용 판매 중 상품 (#571). 측정용 product DB(product_perf_db 등 이름이 _perf_db로 끝나는 DB) 전용, 세미 seed.sql의 상품 부분과 같은 모양
--
-- 넣기: psql -d product_perf_db ... -v products=10000 -v sellers=200 < perf/data/product-seed.sql
--   여러 번 실행하면 그만큼 더 쌓인다 (1만 → 3만: products=20000으로 한 번 더)
-- 넣기 전에: product 앱이 한 번 떠서 테이블 · 품종(Flyway V4 · V11)이 있어야 한다
-- 판매자는 회원 복제본 없이 id만 둔다 (목록 · 검색 응답은 sellerId만 씀). 900001부터라 실제 회원 id와 겹치지 않는다
-- 넣은 뒤: OpenSearch 색인은 관리자 전체 색인 API로 (SQL로 넣으면 색인 이벤트가 없음)

\set ON_ERROR_STOP on

-- 실수 방지 (#596 리뷰): 개수를 안 주거나, 측정용 DB(_perf_db)가 아니면 아무것도 넣지 않고 끝낸다
\if :{?products}
\else
  \echo '-v products=<넣을 상품 수>를 주세요 (예: -v products=10000 -v sellers=200)'
  \quit
\endif
\if :{?sellers}
\else
  \echo '-v sellers=<판매자 수>를 주세요 (예: -v products=10000 -v sellers=200)'
  \quit
\endif
SELECT current_database() LIKE '%\_perf\_db' AS is_perf_db \gset
\if :is_perf_db
\else
  \echo '측정용 DB(이름이 _perf_db로 끝남)에서만 실행합니다. 지금 DB:' :DBNAME
  \quit
\endif
BEGIN;

CREATE TEMP TABLE perf_species ON COMMIT DROP AS
SELECT row_number() OVER (ORDER BY id) AS n, id, name FROM species;

CREATE TEMP TABLE perf_base ON COMMIT DROP AS
SELECT COALESCE(max(id), 0) AS base FROM product;

-- 가격 1만 ~ 10만, 품종 · 판매자를 고르게 나눔 (제목 = 품종 이름 + 번호, 설명에도 품종 이름)
INSERT INTO product (created_at, updated_at, description, grow_note, height_cm, is_hidden, pot_size, price,
                     seller_id, status, title, species_id)
SELECT now() - ((b.base + g) || ' minutes')::interval, now(), sp.name || ' 부하 테스트 상품입니다.', NULL,
       10 + g % 50, false, 'M', (1 + g % 10) * 10000, 900000 + 1 + g % :sellers, 'ACTIVE',
       sp.name || ' ' || (b.base + g), sp.id
FROM generate_series(1, :products) g
CROSS JOIN perf_base b
JOIN perf_species sp ON sp.n = 1 + g % (SELECT count(*) FROM perf_species);

INSERT INTO product_image (s3_url, sort_order, product_id)
SELECT 'https://example.com/perf/' || p.id || '.jpg', 0, p.id
FROM product p, perf_base b
WHERE p.id > b.base;

COMMIT;

ANALYZE product;
ANALYZE product_image;
SELECT count(*) AS products FROM product;
