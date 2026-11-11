package com.justcommit.backend.product.product.application;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;
import com.justcommit.backend.product.ProductQuery;
import com.justcommit.backend.product.ProductResult;
import com.justcommit.backend.product.ProductStatus;
import com.justcommit.backend.product.ProductUseCase;
import com.justcommit.backend.product.client.member.MemberQuery;
import com.justcommit.backend.product.exception.ProductErrorCode;
import com.justcommit.backend.product.image.application.ProductImageCleaner;
import com.justcommit.backend.product.image.application.ProductImageUrlValidator;
import com.justcommit.backend.product.product.domain.Product;
import com.justcommit.backend.product.product.domain.ProductImage;
import com.justcommit.backend.product.product.domain.ReviewScope;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import com.justcommit.backend.product.product.infrastructure.ProductSpecifications;
import com.justcommit.backend.product.product.presentation.dto.*;
import com.justcommit.backend.product.reservation.domain.ProductReservation;
import com.justcommit.backend.product.reservation.infrastructure.ProductReservationRepository;
import com.justcommit.backend.product.species.domain.Species;
import com.justcommit.backend.product.species.infrastructure.SpeciesRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService implements ProductQuery, ProductUseCase {

    // 등록 제한 (정책 「등록 제한」)
    private static final int REVIEW_PENDING_LIMIT = 5;
    private static final int DAILY_REGISTER_LIMIT = 20;

    private final ProductRepository productRepository;
    private final ProductReservationRepository reservationRepository;
    private final SpeciesRepository speciesRepository;
    private final MemberQuery memberQuery;
    private final ProductImageUrlValidator imageUrlValidator;
    private final ProductImageCleaner imageCleaner;
    private final Clock clock;

    // ── ProductQuery ───────────────────────────────

    @Override
    public ProductResult getProduct(Long productId) {
        return productRepository.findById(productId)
                .map(this::toResult)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));
    }

    @Override
    public List<ProductResult> getProducts(List<Long> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findWithSpeciesByIdIn(productIds).stream()
                .map(this::toResult)
                .toList();
    }

    // ── ProductUseCase ─────────────────────────────

    @Override
    @Transactional
    public void reserve(Long orderId, Map<Long, BigDecimal> expectedPrices) {
        if (expectedPrices.isEmpty()) {
            throw new BusinessException(CommonErrorCode.BAD_REQUEST_DATA);
        }

        // 1단계) 예약 기록을 잡고 잠그기
        // 예약 기록을 잠가 같은 orderId의 해제와 순서를 정함 (실패하면 롤백되어 기록도 남지 않음)
        reservationRepository.insertIfAbsent(orderId);
        //행을 잠궈서 같은 주문 번호의 다른 작업(해제) 트랙잭션이 끝날 때까지 기다리도록 함.
        ProductReservation reservation = reservationRepository.findWithLockByOrderId(orderId).orElseThrow();
        //이미 해제되었으면 409
        if (reservation.isReleased()) {
            throw new BusinessException(ProductErrorCode.RESERVATION_RELEASED);
        }

        // 2단계) 같은 주문이 다시 왔는지 확인
        List<Long> ids = List.copyOf(expectedPrices.keySet());
        // 같은 orderId로 다시 온 예약: 이미 이 주문이 모두 잡고 있으면 그대로 성공
        // PAYMENT_PENDING으로 온 주문의 개수를 확인. 전부일 경우 이미 성공한 요청 -> 성공 끝냄(멱등 처리)
        // 이 확인이 없을 경우 3단계에 안거려서 구매불가가 됨 -> 실패
        if (productRepository.countByReservedOrderIdAndStatusAndIdIn(orderId, ProductStatus.PAYMENT_PENDING, ids) == ids.size()) {
            return;
        }

        // 3단계) 조건부 Update 로 한 번에 잡기
        // 구매 가능 조건(ACTIVE · 숨김 아님)을 건 UPDATE 한 번이라 동시에 잡아도 한 주문만 성공. 일부만 잡히면 예외로 롤백
        // 검수 중 · 반려 상품도 ACTIVE가 아니라 여기서 걸려 409
        if (productRepository.reserve(orderId, ids) != ids.size()) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_RESERVABLE);
        }

        // 4단계) 가격 확인
        // 잡은 상품은 커밋 전까지 행이 잠겨 있어 가격이 바뀌지 않음. 하나라도 다르면 롤백
        // 잡은 다음 비교하는 이유 : 그 틈에 가격이 바뀌는 문제를 방지하기 위해
        boolean priceChanged = productRepository.findAllById(ids).stream()
                .anyMatch(product -> product.getPrice().compareTo(expectedPrices.get(product.getId())) != 0);
        if (priceChanged) {
            throw new BusinessException(ProductErrorCode.PRICE_CHANGED);
        }
    }

    @Override
    @Transactional
    public boolean releaseItem(Long productId, Long orderId, boolean hidden) {
        productRepository.lockById(productId); // 판매자 탈퇴 처리와 순서를 맞춤
        return productRepository.releaseItem(productId, orderId, hidden) > 0;
    }

    @Override
    @Transactional
    public boolean release(Long orderId) {
        // 행이 없으면 RELEASED 로 만들고, 있으면 RELEASED 로 바꿈(잠금)
        reservationRepository.upsertReleased(orderId);   // 예약 기록 행을 잠그고 RELEASED로 (늦은 예약 거절)
        productRepository.lockByReservedOrder(orderId);   // 판매자 탈퇴 처리와 순서를 맞춤
        // 해당 주문이 잡은 PAYMENT_PENDING 상품만 ACTIVE 로 (판매자가 탈퇴했으면 DELETED)
        return productRepository.release(orderId) > 0;
    }

    @Override
    @Transactional
    public boolean markSoldOut(Long orderId) {
        return productRepository.markSoldOut(orderId) > 0;
    }

    private ProductResult toResult(Product product) {
        return new ProductResult(
                product.getId(),
                product.getSellerId(),
                product.getTitle(),
                product.getPrice(),
                product.getStatus(),
                product.isHidden(),
                product.getThumbnailUrl(),
                product.getSpecies().getName());
    }

    // ── 상품 API ───────────────────────────────

    @Transactional
    public ProductDetailResponse createProduct(Long sellerId, ProductRequest request) {

        //판매자 여부 확인
        if (!memberQuery.isSeller(sellerId)) {
            throw new BusinessException(ProductErrorCode.NOT_SELLER);
        }

        // 등록 제한 (사진 검사 · 품종 조회보다 먼저: 막힐 요청에 일을 더 하지 않게)
        checkRegisterLimit(sellerId);

        List<String> imageUrls = request.pictureUrls() == null ? List.of() : request.pictureUrls();
        imageUrlValidator.validate(sellerId, imageUrls);

        Species species = speciesRepository.findById(request.speciesId())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));

        // 등록하면 REVIEW_PENDING (검수는 #343)
        Product product = Product.create(
                species, sellerId, request.title(), request.description(), request.price(),
                request.heightCm(), request.potSize(), request.growNote(), imageUrls);

        return ProductDetailResponse.from(productRepository.save(product), memberQuery.getNickname(sellerId));
    }

    // 비용 보호: 검수 중 동시 5개(409), 하루 등록 20건(429, 삭제 포함, 한국 시간 자정 기준)
    // 동시에 등록하면 확인과 저장 사이에 끼어 한도를 1~2건 넘을 수 있다. 비용 보호용이라 허용한다
    private void checkRegisterLimit(Long sellerId) {
        if (productRepository.countBySellerIdAndStatus(sellerId, ProductStatus.REVIEW_PENDING) >= REVIEW_PENDING_LIMIT) {
            throw new BusinessException(ProductErrorCode.REVIEW_PENDING_LIMIT_EXCEEDED);
        }
        LocalDateTime startOfToday = LocalDate.now(clock).atStartOfDay();
        if (productRepository.countBySellerIdAndCreatedAtGreaterThanEqual(sellerId, startOfToday) >= DAILY_REGISTER_LIMIT) {
            throw new BusinessException(ProductErrorCode.DAILY_REGISTER_LIMIT_EXCEEDED);
        }
    }

    // memberId: 로그인하지 않았으면 null
    public ProductDetailResponse getProductDetail(Long productId, Long memberId) {
        Product product = productRepository.findWithDetailById(productId)
                .filter(p -> p.getStatus() != ProductStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        // 검수 중 · 반려 상품은 판매자 본인 외에는 없는 상품처럼 404 (검색에도 안 나옴)
        // ※ 403으로 정하면 이 if 블록만 지우면 됨 (아래 isVisibleTo에서 PRODUCT_HIDDEN)
        if (product.isInReview() && !product.isOwnedBy(memberId)) {
            throw new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND);
        }

        // 삭제와 숨김을 구분해서 프론트가 비공개 안내를 띄울 수 있게 함
        if (!product.isVisibleTo(memberId)) {
            throw new BusinessException(ProductErrorCode.PRODUCT_HIDDEN);
        }

        return ProductDetailResponse.from(product, memberQuery.getNickname(product.getSellerId()));
    }

    public Page<ProductSearchResponse> searchProducts(ProductSearchRequest request) {
        return productRepository.findAll(
                        ProductSpecifications.searchCondition(
                                request.keyword(), request.speciesId(), request.category(),
                                request.light(), request.watering(), request.difficulty(),
                                request.minPrice(), request.maxPrice(), request.onSale()),
                        request.toPageable())
                .map(ProductSearchResponse::from);
    }

    @Transactional
    public ProductDetailResponse updateProduct(Long memberId, Long productId, ProductRequest request) {

        Product product = getOwnedProductForUpdate(memberId, productId);
        List<String> imageUrls = request.pictureUrls() == null ? List.of() : request.pictureUrls();
        imageUrlValidator.validate(product.getSellerId(), imageUrls);

        Species species = speciesRepository.findById(request.speciesId())
                .orElseThrow(() -> new BusinessException(ProductErrorCode.SPECIES_NOT_FOUND));

        List<String> oldImageUrls = imageUrlsOf(product);   // update 전에 꺼내 둠

        // 상태 · 검수 버전은 update 안에서 바뀜. 검수 범위는 #345에서 검수 요청 이벤트에 싣는다
        ReviewScope reviewScope = product.update(species, request.title(), request.description(), request.price(),
                request.heightCm(), request.potSize(), request.growNote(), imageUrls);

        // 기존 사진 중 새 목록에서 빠진 것만 커밋 후 삭제
        imageCleaner.deleteAfterCommit(oldImageUrls.stream()
                .filter(url -> !imageUrls.contains(url))
                .toList());

        return ProductDetailResponse.from(product, memberQuery.getNickname(product.getSellerId()));
    }

    @Transactional
    public void deleteProduct(Long memberId, Long productId) {
        Product product = getOwnedProductForUpdate(memberId, productId);
        product.delete();
        imageCleaner.deleteAfterCommit(imageUrlsOf(product));
    }

    private List<String> imageUrlsOf(Product product) {
        return product.getImages().stream().map(ProductImage::getS3Url).toList();
    }

    private Product getOwnedProductForUpdate(Long memberId, Long productId) {
        Product product = productRepository.findWithLockById(productId)
                .filter(p -> p.getStatus() != ProductStatus.DELETED)
                .orElseThrow(() -> new BusinessException(ProductErrorCode.PRODUCT_NOT_FOUND));

        if (!product.isOwnedBy(memberId)) {
            throw new BusinessException(ProductErrorCode.NOT_PRODUCT_OWNER);
        }

        return product;
    }

    @Transactional
    public void changeHidden(Long memberId, Long productId, boolean hidden) {
        getOwnedProductForUpdate(memberId, productId).changeHidden(hidden);
    }

    public Page<MyProductResponse> getMyProducts(Long sellerId, MyProductSearchRequest request) {
        return productRepository.findAll(
                        ProductSpecifications.sellerCondition(sellerId, request.status(), request.hidden()),
                        request.toPageable())
                .map(MyProductResponse::from);
    }

    // 마이페이지 - Status 별 상품 개수 조회
    public MyProductCountResponse getMyProductCounts(Long sellerId, Boolean hidden) {
        Map<ProductStatus, Long> counts = productRepository.countByStatus(sellerId, hidden).stream()
                .collect(Collectors.toMap(ProductRepository.StatusCount::getStatus, ProductRepository.StatusCount::getCount));
        return MyProductCountResponse.from(counts);
    }
}