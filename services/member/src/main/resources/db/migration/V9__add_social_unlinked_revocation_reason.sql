-- 카카오 연결 해제 웹훅(#422)으로 INACTIVE가 될 때도 토큰 폐기(#409)를 쓰므로 폐기 사유에 SOCIAL_UNLINKED를 추가한다.
ALTER TABLE member_token_revocation
    DROP CONSTRAINT ck_member_token_revocation_reason;

ALTER TABLE member_token_revocation
    ADD CONSTRAINT ck_member_token_revocation_reason
        CHECK (reason IN ('PASSWORD_CHANGED', 'PASSWORD_RESET', 'WITHDRAWN', 'SOCIAL_UNLINKED'));
