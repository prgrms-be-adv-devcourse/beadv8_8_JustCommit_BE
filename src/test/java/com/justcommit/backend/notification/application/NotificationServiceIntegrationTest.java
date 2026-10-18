package com.justcommit.backend.notification.application;

import com.justcommit.backend.common.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.infrastructure.repository.NotificationRepository;
import com.justcommit.backend.notification.presentation.dto.NotificationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {"spring.flyway.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop"})
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, NotificationService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 서비스 트랜잭션이 실제로 커밋되게 한다
class NotificationServiceIntegrationTest {

    private static final Long BUYER = 10L;
    private static final Long OTHER = 99L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("같은 이벤트가 두 번 와도 알림은 한 건만 만든다")
    void createIsIdempotent() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("받는 사람이 없는 이벤트는 알림을 만들지 않는다")
    void skipsWithoutRecipient() {
        notificationService.create(null, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("목록은 본인 알림만 최신순으로, isRead로 거를 수 있다")
    void listsOwnNotifications() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");
        Long pickedUpId = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.SHIPMENT_PICKED_UP).findFirst().orElseThrow().getId();
        notificationService.read(BUYER, pickedUpId);

        var all = notificationService.list(BUYER, null, latestFirst());
        var unread = notificationService.list(BUYER, false, latestFirst());

        assertThat(all.content()).extracting(NotificationResponse::type)
                .containsExactly("SHIPMENT_DELIVERED", "SHIPMENT_PICKED_UP");
        assertThat(unread.content()).extracting(NotificationResponse::type).containsExactly("SHIPMENT_DELIVERED");
    }

    @Test
    @DisplayName("안 읽은 알림 수는 본인 것만 센다")
    void countsOwnUnread() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");

        assertThat(notificationService.unreadCount(BUYER)).isEqualTo(2);
    }

    @Test
    @DisplayName("본인 알림을 읽음 처리하면 읽은 시각이 저장되고, 남의 알림은 찾을 수 없다")
    void readsOnlyOwnNotification() {
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        Long othersId = notificationRepository.findAll().stream()
                .filter(n -> n.getMemberId().equals(OTHER)).findFirst().orElseThrow().getId();
        Long mineId = notificationRepository.findAll().stream()
                .filter(n -> n.getMemberId().equals(BUYER)).findFirst().orElseThrow().getId();

        NotificationResponse read = notificationService.read(BUYER, mineId);

        assertThat(read.isRead()).isTrue();
        assertThat(read.readAt()).isNotNull();
        assertThatThrownBy(() -> notificationService.read(BUYER, othersId)).isInstanceOf(BusinessException.class);
        assertThat(notificationRepository.findById(othersId).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("모두 읽음은 본인 알림만 읽음 처리한다")
    void readAllOnlyOwn() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");

        notificationService.readAll(BUYER);

        assertThat(notificationService.unreadCount(BUYER)).isZero();
        assertThat(notificationService.unreadCount(OTHER)).isEqualTo(1);
    }

    private PageRequest latestFirst() {
        return PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
