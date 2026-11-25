#!/usr/bin/env bash
# 주문용 상품을 잠시 빼 두기 · 되돌리기 (#628)
# 세미 둘러보기는 상품 1만 개일 때 쟀다(주문용 3만 개는 그 뒤에 넣음). MSA도 같은 조건으로 재려고 주문용 상품을
# 측정용 표(perf_parked_product · perf_parked_image)로 옮겼다가 같은 id로 되돌린다.
# 사용: perf/data/msa/park-products.sh park <FIRST_ID> <LAST_ID>   |   perf/data/msa/park-products.sh unpark
# 옮긴 뒤에는 관리자 전체 색인으로 OpenSearch에서도 빼고(삭제 상품은 문서 삭제), 되돌린 뒤 다시 색인한다.
set -euo pipefail
DATA=${DATA_HOST:-replant-data}
psql_db() { ssh -o BatchMode=yes "$DATA" "docker exec -i replant-postgres sh -c 'psql -U \$POSTGRES_USER -d product_db -v ON_ERROR_STOP=1 -P pager=off'"; }

case "${1:-}" in
  park)
    first=${2:?FIRST_ID}; last=${3:?LAST_ID}
    psql_db <<SQL
BEGIN;
CREATE TABLE perf_parked_product AS SELECT * FROM product WHERE id BETWEEN $first AND $last;
CREATE TABLE perf_parked_image AS SELECT * FROM product_image WHERE product_id BETWEEN $first AND $last;
DELETE FROM product_image WHERE product_id BETWEEN $first AND $last;
DELETE FROM product WHERE id BETWEEN $first AND $last;
COMMIT;
VACUUM ANALYZE product; VACUUM ANALYZE product_image;
SELECT (SELECT count(*) FROM perf_parked_product) AS parked, (SELECT count(*) FROM product) AS product_rows;
SQL
    ;;
  unpark)
    psql_db <<'SQL'
BEGIN;
INSERT INTO product SELECT * FROM perf_parked_product;
INSERT INTO product_image SELECT * FROM perf_parked_image;
DROP TABLE perf_parked_product; DROP TABLE perf_parked_image;
COMMIT;
ANALYZE product; ANALYZE product_image;
SELECT count(*) AS product_rows FROM product;
SQL
    ;;
  *) echo "사용: $0 park <FIRST_ID> <LAST_ID> | unpark" >&2; exit 1 ;;
esac
