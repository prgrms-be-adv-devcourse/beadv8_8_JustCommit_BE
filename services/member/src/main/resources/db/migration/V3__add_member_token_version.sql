-- 회원 인증 버전 (#409).
-- 비밀번호 변경 · 탈퇴 때 1 올려, 그 전에 발급한 Refresh를 재발급할 수 없게 한다.
-- JPA @Version이 아니다: 인증 폐기와 무관한 회원 정보 변경에는 올리지 않는다.
-- 기존 회원은 0으로 시작한다.
ALTER TABLE member
    ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

ALTER TABLE member
    ADD CONSTRAINT ck_member_token_version CHECK (token_version >= 0);
