package com.justcommit.backend.notification.domain;

// 한 사람이 아닌 역할 전체가 받는 알림의 받는 역할 (#561). 값은 JWT 역할 클레임과 같은 이름
public enum RecipientRole {
    ADMIN;

    // 로그인한 회원의 역할이 역할 알림을 받는 역할이면 그 값, 아니면 null (역할 알림을 보지 않음)
    public static RecipientRole of(String memberRole) {
        for (RecipientRole role : values()) {
            if (role.name().equals(memberRole)) {
                return role;
            }
        }
        return null;
    }
}
