-- 비밀번호 재설정 (#412)도 토큰 폐기(#409)를 쓰므로 폐기 사유에 PASSWORD_RESET을 추가한다.
-- 비밀번호 변경(PASSWORD_CHANGED)과 구분해 기록한다 (재설정은 계정 탈취가 의심되는 상황일 수 있음).
ALTER TABLE member_token_revocation
    DROP CONSTRAINT ck_member_token_revocation_reason;

ALTER TABLE member_token_revocation
    ADD CONSTRAINT ck_member_token_revocation_reason
        CHECK (reason IN ('PASSWORD_CHANGED', 'PASSWORD_RESET', 'WITHDRAWN'));
