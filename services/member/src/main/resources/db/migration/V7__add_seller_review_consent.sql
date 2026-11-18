-- 판매자 등록 시 상품 검수(외부 AI 이용 · 국외 이전) 동의 버전과 시각 (#418)
ALTER TABLE seller
    ADD COLUMN review_consent_version VARCHAR(30) NOT NULL,
    ADD COLUMN review_consented_at    TIMESTAMP(6) NOT NULL;
