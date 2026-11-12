package com.justcommit.backend.member.address.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.messaging.IntegrationEvent;
import com.justcommit.backend.common.messaging.crypto.EventFieldCipher;
import com.justcommit.backend.common.messaging.outbox.OutboxEventRepository;
import com.justcommit.backend.common.messaging.outbox.OutboxPublisher;
import com.justcommit.backend.common.messaging.outbox.OutboxRelay;
import com.justcommit.backend.member.address.domain.Address;
import com.justcommit.backend.member.address.infrastructure.AddressRepository;
import com.justcommit.backend.member.domain.Member;
import com.justcommit.backend.member.domain.exception.MemberErrorCode;
import com.justcommit.backend.member.event.MemberAddressChanged;
import com.justcommit.backend.member.infrastructure.repository.MemberRepository;
import com.justcommit.backend.member.seller.application.SellerRegisterCommand;
import com.justcommit.backend.member.seller.application.SellerRegisterService;
import com.justcommit.backend.member.seller.domain.Seller;
import com.justcommit.backend.member.seller.infrastructure.SellerRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * 판매자 배송지 이벤트 통합 테스트 (실제 PostgreSQL · 실제 트랜잭션).
 * - 커밋 뒤 Kafka 전송(OutboxRelay)만 테스트 대역으로 바꾸고, Outbox 행 저장(OutboxPublisher)은 실제로 한다
 * - 테스트 데이터는 별도 트랜잭션으로 커밋한 뒤 서비스(Spring 프록시)를 호출한다. 클래스 · 메서드에 @Transactional 없음
 * - 결과는 새 조회로 확인하고, 끝나면 행을 지운다
 */
@SpringBootTest(properties = "outbox.scheduler.enabled=false")
@Testcontainers
@ActiveProfiles("test")
@SuppressWarnings("unchecked")
class SellerAddressEventIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    private static final KeyPair JWT_KEY_PAIR = newKeyPair();
    private static final AtomicInteger SEQ = new AtomicInteger();

    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        registry.add("jwt.private-key", () -> encode(JWT_KEY_PAIR.getPrivate().getEncoded()));
        registry.add("jwt.public-key", () -> encode(JWT_KEY_PAIR.getPublic().getEncoded()));
    }

    // 커밋 뒤 Kafka 전송만 막는다 (Outbox 행 저장은 실제)
    @MockitoBean
    OutboxRelay outboxRelay;

    // 실제 OutboxPublisher를 감싼다. 롤백 테스트에서만 주소 이벤트 발행을 실패시킨다 (테스트마다 자동 초기화)
    @MockitoSpyBean
    OutboxPublisher outboxPublisher;

    @Autowired SellerRegisterService sellerRegisterService;
    @Autowired AddressService addressService;
    @Autowired SellerAddressEventFactory sellerAddressEventFactory;
    @Autowired EventFieldCipher addressEventCipher;
    @Autowired MemberRepository memberRepository;
    @Autowired AddressRepository addressRepository;
    @Autowired SellerRepository sellerRepository;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired PlatformTransactionManager transactionManager;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from outbox_event");
        jdbcTemplate.update("delete from seller");
        jdbcTemplate.update("delete from address");
        jdbcTemplate.update("delete from member");
    }

    @Test
    @DisplayName("판매자 등록 중 주소 이벤트 발행 중 예외가 나면 판매자 저장 · 권한 변경 · 먼저 저장된 MemberUpdated까지 롤백")
    void register_rollsBackOnAddressEventFailure() {
        Long memberId = givenMember(false, 1);
        AtomicLong savedBeforeFailure = new AtomicLong(-1);
        doAnswer(invocation -> {
            // 예외 직전, 같은 트랜잭션에서 MemberUpdated가 실제로 저장됐는지 센다 (JPQL count 전에 자동 flush)
            savedBeforeFailure.set(outboxEventRepository.count());
            throw new IllegalStateException("주소 이벤트 발행 중 예외 (테스트)");
        }).when(outboxPublisher).publish(argThat((IntegrationEvent event) -> event instanceof MemberAddressChanged));

        assertThatThrownBy(() -> sellerRegisterService.register(new SellerRegisterCommand(memberId, null)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(savedBeforeFailure.get()).isEqualTo(1); // 실패 직전엔 MemberUpdated가 있었다
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from seller where member_id = ?", Long.class, memberId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "select role from member where id = ?", String.class, memberId)).isEqualTo("MEMBER");
        assertThat(jdbcTemplate.queryForObject("select count(*) from outbox_event", Long.class)).isZero();
    }

    @Test
    @DisplayName("판매자 등록이 커밋되면 MemberUpdated와 주소 이벤트가 저장되고, 주소 이벤트를 풀면 그 회원의 배송지 목록이다")
    void register_savesBothEvents() {
        Long memberId = givenMember(false, 1);
        Set<String> before = outboxIds();

        sellerRegisterService.register(new SellerRegisterCommand(memberId, null));

        assertThat(jdbcTemplate.queryForList(
                "select event_type from outbox_event where aggregate_id = ?", String.class, String.valueOf(memberId)))
                .containsExactlyInAnyOrder("MemberUpdated", "MemberAddressChanged");
        List<Map<String, Object>> addresses = addresses(newAddressEvent(before, memberId));
        assertThat(addresses).hasSize(1);
        assertThat(addresses.get(0)).containsEntry("isDefault", true).containsEntry("address2", null);
    }

    @Test
    @DisplayName("방금 추가한 배송지 · 바꾼 기본 표시가 같은 트랜잭션의 주소 이벤트에 들어가고, 목록은 DB 정렬(기본 먼저 · 최신 순)이다")
    void addAndChangeDefault_reflectsChangesInEvent() {
        Long memberId = givenMember(true, 1);
        Long first = addressIds(memberId).get(0);

        Set<String> beforeAdd = outboxIds();
        AddressResult added = addressService.addAddress(memberId, new AddressCreateCommand(
                "새사람", "01099998888", "04524", "서울 중구 세종대로 110", null, null, false));
        List<Map<String, Object>> afterAdd = addresses(newAddressEvent(beforeAdd, memberId));
        assertThat(afterAdd).extracting(a -> ((Number) a.get("addressId")).longValue())
                .containsExactly(first, added.addressId());
        assertThat(afterAdd).extracting(a -> a.get("isDefault")).containsExactly(true, false);

        Set<String> beforeChange = outboxIds();
        addressService.changeDefault(memberId, added.addressId());
        List<Map<String, Object>> afterChange = addresses(newAddressEvent(beforeChange, memberId));
        assertThat(afterChange).extracting(a -> ((Number) a.get("addressId")).longValue())
                .containsExactly(added.addressId(), first);
        assertThat(afterChange).extracting(a -> a.get("isDefault")).containsExactly(true, false);
    }

    @Test
    @DisplayName("배송지 3개에서 두 요청이 동시에 추가해도 4개를 넘지 않는다 (결과 검증, 겹침 보장 없음)")
    void concurrentAdd_doesNotExceedLimit() throws Exception {
        Long memberId = givenMember(false, 3);

        List<Object> results = runTogether(
                () -> addressService.addAddress(memberId, command("동시1")),
                () -> addressService.addAddress(memberId, command("동시2")));

        assertThat(results).filteredOn(AddressResult.class::isInstance).hasSize(1);
        assertThat(results).filteredOn(BusinessException.class::isInstance).singleElement()
                .extracting("errorCode").isEqualTo(MemberErrorCode.ADDRESS_LIMIT_EXCEEDED);
        assertThat(addressIds(memberId)).hasSize(4);
    }

    @Test
    @DisplayName("두 요청이 동시에 서로 다른 배송지를 기본으로 바꿔도 기본은 정확히 1개 (결과 검증, 겹침 보장 없음)")
    void concurrentChangeDefault_keepsSingleDefault() throws Exception {
        Long memberId = givenMember(false, 3);
        List<Long> ids = addressIds(memberId);

        List<Object> results = runTogether(
                () -> addressService.changeDefault(memberId, ids.get(1)),
                () -> addressService.changeDefault(memberId, ids.get(2)));

        assertThat(results).allMatch(AddressResult.class::isInstance);
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from address where member_id = ? and is_default", Long.class, memberId)).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 트랜잭션이 회원 잠금을 쥐고 있으면 배송지 추가는 기다렸다가, 잠금이 풀린 뒤 끝난다 (잠금 검증)")
    void addAddress_waitsForMemberLock() throws Exception {
        Long memberId = givenMember(false, 1);
        assertWaitsForMemberLock(memberId, () -> addressService.addAddress(memberId, command("잠금")));
    }

    @Test
    @DisplayName("다른 트랜잭션이 회원 잠금을 쥐고 있으면 기본 배송지 변경은 기다렸다가, 잠금이 풀린 뒤 끝난다 (잠금 검증)")
    void changeDefault_waitsForMemberLock() throws Exception {
        Long memberId = givenMember(false, 2);
        Long second = addressIds(memberId).get(1);
        assertWaitsForMemberLock(memberId, () -> addressService.changeDefault(memberId, second));
    }

    @Test
    @DisplayName("다른 트랜잭션이 회원 잠금을 쥐고 있으면 판매자 등록은 기다렸다가, 잠금이 풀린 뒤 끝난다 (잠금 검증)")
    void register_waitsForMemberLock() throws Exception {
        Long memberId = givenMember(false, 1);
        assertWaitsForMemberLock(memberId,
                () -> sellerRegisterService.register(new SellerRegisterCommand(memberId, null)));
    }

    @Test
    @DisplayName("주입받은 팩토리를 트랜잭션 밖에서 부르면 실행되지 않는다 (MANDATORY)")
    void factory_requiresTransaction() {
        Long memberId = givenMember(true, 1);

        assertThatThrownBy(() -> sellerAddressEventFactory.create(memberId))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // 회원 + 배송지를 만들어 커밋한다 (첫 배송지가 기본). seller면 판매자로 등록된 상태로 만든다
    private Long givenMember(boolean seller, int addressCount) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            int n = SEQ.incrementAndGet();
            Member member = memberRepository.save(Member.createLocal("it" + n + "@test.com", "encoded",
                    "통합" + n, String.format("010%08d", n), "004", "v1:test-account", "홍길동"));
            for (int i = 0; i < addressCount; i++) {
                addressRepository.save(Address.create(member, "받는사람" + i, "01012345678", "06236",
                        "서울 강남구 테헤란로 " + i, null, null, i == 0));
            }
            if (seller) {
                member.promoteToSeller();
                sellerRepository.save(Seller.register(member, null));
            }
            return member.getId();
        });
    }

    private List<Long> addressIds(Long memberId) {
        return jdbcTemplate.queryForList("select id from address where member_id = ? order by id", Long.class, memberId);
    }

    private Set<String> outboxIds() {
        return new HashSet<>(jdbcTemplate.queryForList("select id::text from outbox_event", String.class));
    }

    // before 이후 새로 생긴 주소 이벤트 하나를 찾아, 봉투 · 암호문을 풀어 주소 JSON을 돌려준다 (시각 정렬에 기대지 않음)
    private Map<String, Object> newAddressEvent(Set<String> before, Long memberId) {
        List<Map<String, Object>> added = jdbcTemplate.queryForList(
                "select id::text as id, aggregate_id, payload from outbox_event where event_type = 'MemberAddressChanged'")
                .stream().filter(row -> !before.contains((String) row.get("id"))).toList();
        assertThat(added).hasSize(1);
        Map<String, Object> row = added.get(0);
        assertThat(row.get("aggregate_id")).isEqualTo(String.valueOf(memberId));

        Map<String, Object> envelope = jsonMapper.readValue((String) row.get("payload"), Map.class);
        Map<String, Object> payload = (Map<String, Object>) envelope.get("payload");
        assertThat(((Number) payload.get("memberId")).longValue()).isEqualTo(memberId);

        Map<String, Object> decrypted = jsonMapper.readValue(
                addressEventCipher.decrypt((String) payload.get("encryptedAddresses")), Map.class);
        assertThat(((Number) decrypted.get("memberId")).longValue()).isEqualTo(memberId);
        return decrypted;
    }

    private List<Map<String, Object>> addresses(Map<String, Object> decrypted) {
        return (List<Map<String, Object>>) decrypted.get("addresses");
    }

    private AddressCreateCommand command(String recipientName) {
        return new AddressCreateCommand(recipientName, "01012345678", "06236", "서울 강남구 테헤란로 1",
                null, null, false);
    }

    // 두 요청을 같은 순간에 시작시키고 각각의 결과(성공 값 또는 예외)를 돌려준다. 실제로 겹쳐 실행된다는 보장은 없다
    private List<Object> runTogether(Callable<?> first, Callable<?> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (Callable<?> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Object> results = new ArrayList<>();
            for (Future<?> future : futures) {
                try {
                    results.add(future.get(10, TimeUnit.SECONDS));
                } catch (ExecutionException e) {
                    results.add(e.getCause());
                }
            }
            return results;
        } finally {
            // 실패 · 시간 초과여도 작업을 멈추고 스레드가 끝날 때까지 기다린 뒤 DB 정리로 넘어간다
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // 다른 트랜잭션이 회원 행 잠금을 쥐고 있는 동안 action이 끝나지 않고, 잠금을 풀면 끝나는지 확인한다
    private void assertWaitsForMemberLock(Long memberId, Callable<?> action) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                memberRepository.findByIdForUpdate(memberId).orElseThrow();
                locked.countDown();
                awaitQuietly(release);
                return null;
            })));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> waiting = executor.submit(action);
            futures.add(waiting);
            // 잠금을 쥐고 있는 동안에는 끝나지 않아야 한다
            assertThatThrownBy(() -> waiting.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            release.countDown();
            waiting.get(10, TimeUnit.SECONDS);
            futures.get(0).get(10, TimeUnit.SECONDS);
        } finally {
            // 실패해도 잠금을 쥔 트랜잭션부터 끝내고, 남은 작업을 멈춘 뒤 스레드 종료를 기다린다
            release.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("잠금 해제 신호를 기다리다 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static KeyPair newKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(byte[] der) {
        return Base64.getEncoder().encodeToString(der);
    }
}
