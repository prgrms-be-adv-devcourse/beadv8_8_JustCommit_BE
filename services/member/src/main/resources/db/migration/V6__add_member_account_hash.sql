-- 계좌 중복 확인용 HMAC (#413). 값은 MemberHmac.accountHash (hex 64글자)
-- 기존 회원은 NULL로 둔다 (운영 전 회원 데이터 초기화 전제). PostgreSQL UNIQUE는 NULL 여러 개를 허용한다
ALTER TABLE member
    ADD COLUMN account_hash VARCHAR(64),
    ADD CONSTRAINT uk_member_account_hash UNIQUE (account_hash);
