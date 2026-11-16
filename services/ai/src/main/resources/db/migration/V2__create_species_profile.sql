-- 품종 프로필 · 품종 벡터 (#292, ADR 0020)
-- species_profile: 농사로 실내정원용 식물 상세로 만든 품종 정보. 데이터는 V3 시드, 바꿀 때는 새 버전을 추가한다
-- species_id는 product species.id와 같은 고정값 (215종, 18 · 178 없음, 「기타」 218은 넣지 않음)
CREATE TABLE species_profile (
    species_id        INTEGER      PRIMARY KEY,
    nongsaro_no       VARCHAR(10)  NOT NULL UNIQUE,  -- 농사로 cntntsNo
    name              VARCHAR(100) NOT NULL,          -- product species.name과 같음
    scientific_name   VARCHAR(200),
    genus             VARCHAR(50),                    -- 학명 첫 단어 (같은 속 제한, #294)
    english_name      VARCHAR(200),
    distribution_name VARCHAR(300),                   -- 유통명
    family_name       VARCHAR(100),
    category_names    VARCHAR(200),                   -- 잎보기식물 등 (원문)
    leaf_color_names  VARCHAR(200),
    difficulty        VARCHAR(10),                    -- EASY · NORMAL · HARD (빈 값 있음)
    light_names       VARCHAR(200),                   -- 광도 원문
    light_min         VARCHAR(10),                    -- LOW · MEDIUM · HIGH
    light_max         VARCHAR(10),
    placement_names   VARCHAR(300),                   -- 배치 장소 원문
    growth_temp_name  VARCHAR(50),                    -- 생육 온도 원문
    growth_temp_min   SMALLINT,
    growth_temp_max   SMALLINT,
    winter_min_temp   SMALLINT,                       -- 겨울 최저 온도 (℃)
    humidity_name     VARCHAR(50),
    water_spring      VARCHAR(100),
    water_summer      VARCHAR(100),
    water_autumn      VARCHAR(100),
    water_winter      VARCHAR(100),
    features          TEXT,                           -- 특징 (fncltyInfo)
    special_care      TEXT,                           -- 특별 관리 (speclmanageInfo)
    advice            TEXT,                           -- 조언 (adviseInfo)
    pests             TEXT,                           -- 병충해 관리 (dlthtsManageInfo)
    toxicity          TEXT                            -- 독성 (toxctyInfo)
);

-- 품종 벡터: Spring AI PgVectorStore 형식 (id · content · metadata · embedding). id = species_id
-- metadata.documentHash로 문서가 그대로인지 보고 재임베딩을 건너뛴다
-- 차원을 바꾸면 이 칸과 저장된 벡터를 모두 다시 만들어야 한다 (docs/services/ai.md 임베딩 모델)
CREATE TABLE species_vector_store (
    id        INTEGER PRIMARY KEY,
    content   TEXT,
    metadata  JSON,
    embedding VECTOR(1536)
);

CREATE INDEX species_vector_store_embedding_idx
    ON species_vector_store USING hnsw (embedding vector_cosine_ops);
