package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.member.domain.MemberReplica;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.event.ProductAdminHidden;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.infrastructure.ProductSpecifications;
import com.justcommit.backend.product.product.presentation.dto.AdminProductResponse;
import com.justcommit.backend.product.product.presentation.dto.AdminProductSearchRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;

// 관리자 상품 관리 (#623): 상품 목록 · 강제 숨김 · 해제. ADMIN만 (SecurityConfig의 /api/v1/admin/**)
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminProductService {

    static final int MAX_REASON_LENGTH = 200;

    private final ProductRepository productRepository;
    private final MemberQuery memberQuery;
    private final ProductEventPublisher productEventPublisher;
    private final Clock clock;

    // 삭제 제외 전체 상품, 최신 등록 순. 판매자 닉네임은 회원 복제본에서 한 번에
    @Transactional(readOnly = true)
    public Page<AdminProductResponse> getProducts(AdminProductSearchRequest request) {
        Page<Product> page = productRepository.findAll(
                ProductSpecifications.adminCondition(request.keyword(), request.sellerId(), request.status(),
                        request.adminHidden()),
                request.toPageable());
        Map<Long, String> nicknames = memberQuery.getNicknames(
                page.getContent().stream().map(Product::getSellerId).distinct().toList());
        return page.map(product -> AdminProductResponse.of(product,
                nicknames.getOrDefault(product.getSellerId(), MemberReplica.UNKNOWN_NICKNAME)));
    }

    // 강제 숨김: 판매자에게 알림(ProductAdminHidden) + 상태 동기화(ProductStatusChanged). 이미 숨겼으면 아무것도 안 함
    // 해제: 상태 동기화만 (알림 없음). 판매자 숨김은 어느 쪽에서도 건드리지 않음
    @Transactional
    public void changeHidden(Long adminId, Long productId, boolean hidden, String reason) {
        Product product = productRepository.findWithLockById(productId)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
        if (hidden) {
            hide(adminId, product, reason);
        } else if (product.unhideByAdmin()) {
            log.info("관리자 숨김 해제: productId={}, adminId={}", productId, adminId);
            productEventPublisher.statusChanged(productId);
        }
    }

    private void hide(Long adminId, Product product, String reason) {
        String trimmed = reason == null ? null : reason.trim();
        if (!StringUtils.hasText(trimmed) || trimmed.length() > MAX_REASON_LENGTH) {
            throw new BusinessException(ProductErrorCode.INVALID_ADMIN_HIDE_REASON);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (!product.hideByAdmin(trimmed, now)) {
            return;
        }
        log.info("관리자 강제 숨김: productId={}, adminId={}", product.getId(), adminId);
        productEventPublisher.statusChanged(product.getId());
        productEventPublisher.adminHidden(new ProductAdminHidden(
                product.getId(), product.getSellerId(), product.getTitle(), trimmed, now));
    }
}
