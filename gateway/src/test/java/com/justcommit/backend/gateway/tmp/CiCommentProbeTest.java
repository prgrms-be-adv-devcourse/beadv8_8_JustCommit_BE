package com.justcommit.backend.gateway.tmp;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// #534 CI 댓글 확인용 임시 테스트 — 확인 뒤 되돌린다
class CiCommentProbeTest {

    @Test
    @DisplayName("CI 댓글 확인용으로 일부러 실패한다")
    void failOnPurpose() {
        assertThat(1 + 1).isEqualTo(3);
    }
}
