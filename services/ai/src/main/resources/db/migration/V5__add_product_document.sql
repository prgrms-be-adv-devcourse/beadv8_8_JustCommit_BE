-- 챗봇 상품 의미 검색 (#298, ADR 0031)
-- 상품 이벤트 payload v3의 상품 문서 · 카드 값을 스냅샷에 더한다. v2 이벤트로 저장된 행은 NULL (다음 이벤트에서 채워짐)
ALTER TABLE product_snapshot
    ADD COLUMN title         VARCHAR(100),
    ADD COLUMN price         NUMERIC(19, 0),
    ADD COLUMN thumbnail_url TEXT,
    ADD COLUMN description   TEXT,
    ADD COLUMN grow_note     TEXT,
    ADD COLUMN height_cm     INTEGER,
    ADD COLUMN pot_size      VARCHAR(20);

-- 상품 벡터: Spring AI PgVectorStore 형식 (species_vector_store와 같음). id = product_id
-- 판매 중 · 숨김 여부는 여기 두지 않고 검색할 때 product_snapshot과 조인해 거른다 (값이 한 곳에만 있어 어긋나지 않음)
-- metadata.documentHash로 문서가 그대로인지 보고 재임베딩을 건너뛴다 (상태만 바뀐 이벤트는 임베딩하지 않음)
CREATE TABLE product_vector_store (
    id        BIGINT PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1536)
);

CREATE INDEX product_vector_store_embedding_idx
    ON product_vector_store USING hnsw (embedding vector_cosine_ops);
