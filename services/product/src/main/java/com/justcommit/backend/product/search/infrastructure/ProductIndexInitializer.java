package com.justcommit.backend.product.search.infrastructure;

import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch._types.OpenSearchException;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 앱이 뜰 때 상품 검색 인덱스를 준비한다.
 *
 * <p>앱은 항상 alias {@code products}로 읽고 쓰고, 실제 인덱스는 버전이 붙은 {@code products_v1}이다.
 * 분석기 · 매핑을 바꿀 때는 관리자 재색인 API로 {@code products_v2}를 만들어 채운 뒤 alias만 옮긴다 (무중단 재색인, #487).
 *
 * <p>OpenSearch가 꺼져 있어도 앱은 뜬다. 원본은 DB이고 검색은 LIKE로 대신하기 때문 (docs/conventions/resilience.md).
 * 이때는 경고 로그를 남기고, 준비될 때까지 1분마다 다시 시도한다 (앱을 다시 띄우지 않아도 됨).
 */
@Slf4j
@Component
public class ProductIndexInitializer {

    public static final String ALIAS = ProductIndexAdmin.ALIAS;
    static final String INDEX_V1 = ProductIndexAdmin.FIRST_INDEX;

    private final ProductIndexAdmin indexAdmin;

    public ProductIndexInitializer(ProductIndexAdmin indexAdmin) {
        this.indexAdmin = indexAdmin;
    }

    // 인덱스 · alias가 준비됐는지. 준비되면 재시도를 멈춘다
    private volatile boolean ready;
    // 실패 원인(스택)은 처음 한 번만 남기고, 재시도 중에는 한 줄만 (OpenSearch가 오래 꺼져 있을 때 로그가 넘치지 않게)
    private volatile boolean failureLogged;

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            createIndexIfAbsent();
            ready = true;
            failureLogged = false;
        } catch (Exception e) {
            if (failureLogged) {
                log.warn("상품 검색 인덱스를 아직 준비하지 못함 (1분 뒤 다시 시도): {}", e.toString());
            } else {
                log.warn("상품 검색 인덱스를 준비하지 못함 (OpenSearch 연결 확인, 검색은 LIKE로 대신, 1분마다 다시 시도)", e);
                failureLogged = true;
            }
        }
    }

    // 앱이 뜰 때 OpenSearch가 꺼져 있었다면, 준비될 때까지 다시 시도 (docs/reference/schedulers.md 「상품 검색 인덱스 준비」)
    @Scheduled(fixedDelayString = "${custom.search.index-init.retry-interval:PT1M}",
            initialDelayString = "${custom.search.index-init.retry-interval:PT1M}")
    public void retryUntilReady() {
        if (!ready) {
            initialize();
        }
    }

    boolean isReady() {
        return ready;
    }

    // alias가 이미 있으면 (v1이든 재색인한 v2 이후든) 그대로 둔다. 없으면 products_v1을 만들면서 alias를 함께 붙인다
    void createIndexIfAbsent() throws IOException {
        if (indexAdmin.aliasExists()) {
            log.info("상품 검색 alias '{}'가 이미 있음", ALIAS);
            return;
        }
        try {
            indexAdmin.create(INDEX_V1, true);
            log.info("상품 검색 인덱스 '{}' 생성, alias '{}' 연결", INDEX_V1, ALIAS);
        } catch (OpenSearchException e) {
            // 서버 두 대가 동시에 떠서 다른 쪽이 먼저 만든 경우
            if ("resource_already_exists_exception".equals(e.error().type())) {
                log.info("상품 검색 인덱스 '{}'를 다른 인스턴스가 먼저 만듦", INDEX_V1);
                return;
            }
            throw e;
        }
    }
}
