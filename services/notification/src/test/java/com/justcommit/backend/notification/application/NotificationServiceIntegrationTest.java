package com.justcommit.backend.notification.application;

import com.justcommit.backend.notification.config.JpaAuditingConfig;
import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.notification.domain.RecipientRole;
import com.justcommit.backend.notification.client.market.ShipmentQuery;
import com.justcommit.backend.notification.client.market.ShipmentResult;
import com.justcommit.backend.notification.domain.NotificationType;
import com.justcommit.backend.notification.infrastructure.repository.NotificationRepository;
import com.justcommit.backend.notification.infrastructure.sse.NotificationSseRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.justcommit.backend.common.testcontainers.SharedPostgres;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.util.Set;

@DataJpaTest // 테이블은 Flyway V1이 만들고 ddl-auto validate로 검사한다 (운영과 같은 스키마)
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, NotificationService.class})
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED) // 서비스 트랜잭션이 실제로 커밋되게 한다
class NotificationServiceIntegrationTest {

    private static final Long BUYER = 10L;
    private static final Long OTHER = 99L;
    private static final Long SELLER = 20L;
    private static final Long ADMIN_A = 1L;
    private static final Long ADMIN_B = 2L;

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        SharedPostgres.register(registry);
    }

    @MockitoBean NotificationSseRegistry sseRegistry;
    @MockitoBean ShipmentQuery shipmentQuery;

    @Autowired NotificationService notificationService;
    @Autowired NotificationRepository notificationRepository;
    @Autowired PlatformTransactionManager transactionManager;

    @AfterEach
    void cleanUp() {
        notificationRepository.deleteAll();
    }

    @Test
    @DisplayName("[S-H02] 같은 이벤트가 두 번 와도 알림은 한 건만 만든다")
    void createIsIdempotent() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        assertThat(notificationRepository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("검수 알림은 상품 id와 상품명을 저장하고, 반려 사유를 문구 뒤에 붙인다")
    void createsProductNotification() {
        notificationService.createForProduct(SELLER, NotificationType.PRODUCT_REJECTED, "사진이 흐려요.", 30L,
                "몬스테라 알보", "PRODUCT_REJECTED:30:1");
        notificationService.createForProduct(SELLER, NotificationType.PRODUCT_REJECTED, "사진이 흐려요.", 30L,
                "몬스테라 알보", "PRODUCT_REJECTED:30:1");

        assertThat(notificationRepository.findAll()).singleElement().satisfies(n -> {
            assertThat(n.getMessage()).isEqualTo("검수에서 반려됐어요. 사유: 사진이 흐려요.");
            assertThat(n.getProductId()).isEqualTo(30L);
            assertThat(n.getItemSummary()).isEqualTo("몬스테라 알보");
            assertThat(n.getOrderId()).isNull();
            assertThat(n.getSellerOrderId()).isNull();
        });
        assertThat(notificationService.list(member(SELLER), null, latestFirst()).content())
                .extracting(NotificationResponse::productId).containsExactly(30L);
    }

    @Test
    @DisplayName("반려 사유가 길어 문구가 255자를 넘으면 잘라서 저장한다")
    void truncatesLongRejectReason() {
        notificationService.createForProduct(SELLER, NotificationType.PRODUCT_REJECTED, "가".repeat(300), 30L,
                "몬스테라", "PRODUCT_REJECTED:30:2");

        assertThat(notificationRepository.findAll()).singleElement()
                .satisfies(n -> assertThat(n.getMessage()).hasSize(255).startsWith("검수에서 반려됐어요. 사유: 가"));
    }

    @Test
    @DisplayName("관리자 역할 알림은 member_id 없이 1건 저장되고, 관리자 누구나 보지만 일반 회원은 못 본다")
    void roleNotificationIsVisibleOnlyToAdmins() {
        notificationService.createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED,
                "검수 확인이 늦어진 상품이 3건 있어요. 24시간이 지나면 자동 반려돼요.", "ADMIN_REVIEW_DELAYED:e1");
        notificationService.createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED,
                "검수 확인이 늦어진 상품이 3건 있어요. 24시간이 지나면 자동 반려돼요.", "ADMIN_REVIEW_DELAYED:e1");
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        assertThat(notificationRepository.findAll())
                .filteredOn(n -> n.getRecipientRole() == RecipientRole.ADMIN)
                .singleElement()
                .satisfies(n -> assertThat(n.getMemberId()).isNull());
        assertThat(notificationService.list(admin(ADMIN_A), null, latestFirst()).content())
                .extracting(NotificationResponse::type).containsExactly("ADMIN_REVIEW_DELAYED");
        assertThat(notificationService.unreadCount(admin(ADMIN_B))).isEqualTo(1);
        // 일반 회원은 자기 알림만 (역할 알림이 섞이지 않음)
        assertThat(notificationService.list(member(BUYER), null, latestFirst()).content())
                .extracting(NotificationResponse::type).containsExactly("SHIPMENT_PICKED_UP");
        assertThat(notificationService.unreadCount(member(OTHER))).isZero();
    }

    @Test
    @DisplayName("관리자 한 명이 역할 알림을 읽으면 다른 관리자에게도 읽음이고, 일반 회원은 읽을 수 없다")
    void roleNotificationReadIsShared() {
        notificationService.createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED, "지연 2건",
                "ADMIN_REVIEW_DELAYED:e2");
        Long id = notificationRepository.findAll().getFirst().getId();

        assertThatThrownBy(() -> notificationService.read(member(BUYER), id)).isInstanceOf(BusinessException.class);
        notificationService.read(admin(ADMIN_A), id);

        assertThat(notificationService.unreadCount(admin(ADMIN_B))).isZero();
    }

    @Test
    @DisplayName("역할 알림이 저장되면 연결 중인 관리자 모두에게 각자의 안 읽은 수를 보낸다")
    void pushesRoleNotificationToConnectedAdmins() {
        when(sseRegistry.membersWithRole(RecipientRole.ADMIN)).thenReturn(Set.of(ADMIN_A, ADMIN_B));
        when(sseRegistry.hasConnection(ADMIN_A)).thenReturn(true);
        when(sseRegistry.hasConnection(ADMIN_B)).thenReturn(true);
        when(sseRegistry.roleOf(ADMIN_A)).thenReturn(RecipientRole.ADMIN);
        when(sseRegistry.roleOf(ADMIN_B)).thenReturn(RecipientRole.ADMIN);

        notificationService.createForRole(RecipientRole.ADMIN, NotificationType.ADMIN_REVIEW_DELAYED, "지연 1건",
                "ADMIN_REVIEW_DELAYED:e3");

        verify(sseRegistry).sendUnreadCount(ADMIN_A, 1);
        verify(sseRegistry).sendUnreadCount(ADMIN_B, 1);
    }

    @Test
    @DisplayName("받는 사람이 없는 이벤트는 알림을 만들지 않는다")
    void skipsWithoutRecipient() {
        notificationService.create(null, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("[S-I04] 목록은 본인 알림만 최신순으로, isRead로 거를 수 있다")
    void listsOwnNotifications() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");
        Long pickedUpId = notificationRepository.findAll().stream()
                .filter(n -> n.getType() == NotificationType.SHIPMENT_PICKED_UP).findFirst().orElseThrow().getId();
        notificationService.read(member(BUYER), pickedUpId);

        var all = notificationService.list(member(BUYER), null, latestFirst());
        var unread = notificationService.list(member(BUYER), false, latestFirst());

        assertThat(all.content()).extracting(NotificationResponse::type)
                .containsExactly("SHIPMENT_DELIVERED", "SHIPMENT_PICKED_UP");
        assertThat(unread.content()).extracting(NotificationResponse::type).containsExactly("SHIPMENT_DELIVERED");
        var read = notificationService.list(member(BUYER), true, latestFirst());
        assertThat(read.content()).extracting(NotificationResponse::type).containsExactly("SHIPMENT_PICKED_UP");
    }

    @Test
    @DisplayName("안 읽은 알림 수는 본인 것만 센다")
    void countsOwnUnread() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");

        assertThat(notificationService.unreadCount(member(BUYER))).isEqualTo(2);
    }

    @Test
    @DisplayName("[S-A07] 본인 알림을 읽음 처리하면 읽은 시각이 저장되고, 남의 알림은 찾을 수 없다")
    void readsOnlyOwnNotification() {
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        Long othersId = notificationRepository.findAll().stream()
                .filter(n -> n.getMemberId().equals(OTHER)).findFirst().orElseThrow().getId();
        Long mineId = notificationRepository.findAll().stream()
                .filter(n -> n.getMemberId().equals(BUYER)).findFirst().orElseThrow().getId();

        NotificationResponse read = notificationService.read(member(BUYER), mineId);

        assertThat(read.isRead()).isTrue();
        assertThat(read.readAt()).isNotNull();
        assertThatThrownBy(() -> notificationService.read(member(BUYER), othersId)).isInstanceOf(BusinessException.class);
        assertThat(notificationRepository.findById(othersId).orElseThrow().isRead()).isFalse();
    }

    @Test
    @DisplayName("모두 읽음은 본인 알림만 읽음 처리한다")
    void readAllOnlyOwn() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");
        notificationService.create(OTHER, NotificationType.SHIPMENT_BOOKED, 8L, "SHIPMENT:2:BOOKED");

        notificationService.readAll(member(BUYER));

        assertThat(notificationService.unreadCount(member(BUYER))).isZero();
        assertThat(notificationService.unreadCount(member(OTHER))).isEqualTo(1);
    }

    @Test
    @DisplayName("[S-I02] 구독 중인 회원에게 알림이 저장되면 커밋 후 안 읽은 수를 보낸다")
    void pushesUnreadCountToSubscriber() {
        when(sseRegistry.hasConnection(BUYER)).thenReturn(true);

        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 7L, "SHIPMENT:1:DELIVERED");

        verify(sseRegistry).sendUnreadCount(BUYER, 1);
        verify(sseRegistry).sendUnreadCount(BUYER, 2);
    }

    @Test
    @DisplayName("[S-I02] 알림을 저장한 트랜잭션이 롤백되면 안 읽은 수를 보내지 않고 알림도 남지 않는다")
    void doesNotPushWhenRolledBack() {
        when(sseRegistry.hasConnection(BUYER)).thenReturn(true);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
            status.setRollbackOnly();
        });

        verify(sseRegistry, never()).sendUnreadCount(anyLong(), anyLong());
        assertThat(notificationRepository.count()).isZero();
    }

    @Test
    @DisplayName("[S-I02] 모두 읽음 처리하면 구독 중인 다른 탭에도 0을 보낸다")
    void pushesZeroAfterReadAll() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");
        when(sseRegistry.hasConnection(BUYER)).thenReturn(true);

        notificationService.readAll(member(BUYER));

        verify(sseRegistry).sendUnreadCount(BUYER, 0);
    }

    @Test
    @DisplayName("구독하지 않은 회원에게는 보내지 않는다")
    void doesNotPushWithoutSubscriber() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_PICKED_UP, 7L, "SHIPMENT:1:PICKED_UP");

        verify(sseRegistry, never()).sendUnreadCount(anyLong(), anyLong());
    }

    @Test
    @DisplayName("구매자 알림은 주문 id를 함께 저장해 응답에 내려준다")
    void returnsOrderId() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 9L, 7L, "SHIPMENT:1:DELIVERED");

        var list = notificationService.list(member(BUYER), null, latestFirst());

        assertThat(list.content().getFirst().orderId()).isEqualTo(9L);
        assertThat(list.content().getFirst().sellerOrderId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("배송 이벤트로 받은 상품 요약은 조회 없이 그대로 저장한다")
    void storesGivenItemSummary() {
        notificationService.create(BUYER, NotificationType.SHIPMENT_DELIVERED, 9L, 7L, "몬스테라 외 1건",
                "SHIPMENT:1:DELIVERED");

        assertThat(notificationService.list(member(BUYER), null, latestFirst()).content().getFirst().itemSummary())
                .isEqualTo("몬스테라 외 1건");
        verify(shipmentQuery, never()).findBySellerOrderId(anyLong());
    }

    @Test
    @DisplayName("주문 이벤트 알림은 판매자 주문의 배송에서 상품 요약을 가져온다")
    void looksUpItemSummaryFromShipment() {
        when(shipmentQuery.findBySellerOrderId(7L)).thenReturn(new ShipmentResult(30L, "READY", null, null,
                null, null, null, "해바라기 외 1건"));

        notificationService.create(SELLER, NotificationType.SELLER_ORDER_PAID, 7L, "SELLER_ORDER_PAID:7");

        assertThat(notificationService.list(member(SELLER), null, latestFirst()).content().getFirst().itemSummary())
                .isEqualTo("해바라기 외 1건");
    }

    @Test
    @DisplayName("상품 요약을 못 가져와도 알림은 만들고 상품 요약은 비워 둔다")
    void createsWithoutItemSummaryWhenLookupFails() {
        when(shipmentQuery.findBySellerOrderId(7L)).thenThrow(new IllegalStateException("조회 실패"));

        notificationService.create(SELLER, NotificationType.SELLER_ORDER_PAID, 7L, "SELLER_ORDER_PAID:7");

        assertThat(notificationService.list(member(SELLER), null, latestFirst()).content())
                .singleElement().satisfies(n -> assertThat(n.itemSummary()).isNull());
    }

    @Test
    @DisplayName("[S-H02] 중복 이벤트면 상품 요약도 조회하지 않는다")
    void skipsLookupForDuplicate() {
        notificationService.create(SELLER, NotificationType.SELLER_ORDER_PAID, 7L, "SELLER_ORDER_PAID:7");
        notificationService.create(SELLER, NotificationType.SELLER_ORDER_PAID, 7L, "SELLER_ORDER_PAID:7");

        verify(shipmentQuery, times(1)).findBySellerOrderId(7L);
    }

    private static AuthMember member(Long memberId) {
        return new AuthMember(memberId, "BUYER");
    }

    private static AuthMember admin(Long memberId) {
        return new AuthMember(memberId, "ADMIN");
    }

    private PageRequest latestFirst() {
        return PageRequest.of(0, 20, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
    }
}
