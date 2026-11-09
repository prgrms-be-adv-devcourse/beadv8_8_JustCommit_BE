package com.justcommit.backend.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Column;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NotificationTypeTest {

    @Test
    @DisplayName("알림 종류 이름은 type 컬럼 길이를 넘지 않는다")
    void namesFitTypeColumn() throws NoSuchFieldException {
        int length = Notification.class.getDeclaredField("type").getAnnotation(Column.class).length();

        for (NotificationType type : NotificationType.values()) {
            assertThat(type.name()).as(type.name()).hasSizeLessThanOrEqualTo(length);
        }
    }
}
