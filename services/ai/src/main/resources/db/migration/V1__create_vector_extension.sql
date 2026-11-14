-- pgvector 확장 (#290). 품종 · 상품 벡터 테이블은 각 Task의 다음 버전에서 만든다 (#292 · #293)
-- vector는 superuser만 만들 수 있다: 로컬은 docker/postgres/01-service-databases.sql, 운영은 인프라 절차가 미리 만들고
-- 이 문장은 IF NOT EXISTS라 그냥 지나간다 (Testcontainers는 superuser로 접속해 여기서 만든다)
CREATE EXTENSION IF NOT EXISTS vector;
