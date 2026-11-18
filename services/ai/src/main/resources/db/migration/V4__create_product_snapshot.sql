-- 상품 스냅샷 (#293, ADR 0021)
-- product.events.v1의 ProductRegistered · ProductUpdated · ProductStatusChanged payload로 채운다. ai는 product를 조회하지 않는다
-- 모든 상태를 저장한다: 추천 후보는 ACTIVE이고 숨김이 아닌 것만 고르고, 최근 구매 상품(SOLD_OUT)은 품종으로 바꿀 때 쓴다 (#294)
CREATE TABLE product_snapshot (
    product_id      BIGINT      PRIMARY KEY,           -- 상품 ID (Kafka Key와 같음)
    species_id      BIGINT      NOT NULL,              -- 품종 ID (= species_profile.species_id, 「기타」 218도 그대로 저장)
    seller_id       BIGINT      NOT NULL,              -- 판매자 회원 ID
    status          VARCHAR(20) NOT NULL,              -- REVIEW_PENDING · REJECTED · ACTIVE · PAYMENT_PENDING · SOLD_OUT · DELETED
    hidden          BOOLEAN     NOT NULL,
    product_version BIGINT      NOT NULL,              -- payload productVersion. 이 값보다 클 때만 덮어쓴다 (같으면 중복, 작으면 늦게 온 이벤트)
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now() -- 마지막으로 반영한 시각 (확인용)
);

-- 추천: 「이 품종들의 판매 중 상품」을 찾는다 (WHERE species_id IN (...) AND status = 'ACTIVE')
CREATE INDEX product_snapshot_species_status_idx ON product_snapshot (species_id, status);
