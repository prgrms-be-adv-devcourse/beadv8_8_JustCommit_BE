package com.justcommit.backend.ai.product.infrastructure;

import com.justcommit.backend.ai.product.domain.ProductSnapshot;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

// product_snapshot 저장 · 조회 (JDBC)
@Repository
@RequiredArgsConstructor
public class ProductSnapshotRepository {

    private static final String COLUMNS = """
            product_id, species_id, seller_id, status, hidden, product_version,
            title, price, thumbnail_url, description, grow_note, height_cm, pot_size""";

    private final JdbcClient jdbcClient;

    /**
     * 없으면 넣고, 있으면 받은 productVersion이 저장된 값보다 클 때만 덮어쓴다.
     * 같은 버전(중복) · 낮은 버전(늦게 온 이벤트)이면 WHERE에 걸려 아무것도 바꾸지 않는다.
     * 비교와 쓰기가 SQL 한 문장이라, 같은 상품을 동시에 처리해도 오래된 값이 최신 값을 덮지 않는다.
     *
     * @return 반영했으면 true, 버렸으면 false
     */
    public boolean upsertIfNewer(ProductSnapshot snapshot) {
        int changed = jdbcClient.sql("""
                        INSERT INTO product_snapshot (%s, updated_at)
                        VALUES (:productId, :speciesId, :sellerId, :status, :hidden, :productVersion,
                                :title, :price, :thumbnailUrl, :description, :growNote, :heightCm, :potSize, now())
                        ON CONFLICT (product_id) DO UPDATE SET
                            species_id      = EXCLUDED.species_id,
                            seller_id       = EXCLUDED.seller_id,
                            status          = EXCLUDED.status,
                            hidden          = EXCLUDED.hidden,
                            product_version = EXCLUDED.product_version,
                            title           = EXCLUDED.title,
                            price           = EXCLUDED.price,
                            thumbnail_url   = EXCLUDED.thumbnail_url,
                            description     = EXCLUDED.description,
                            grow_note       = EXCLUDED.grow_note,
                            height_cm       = EXCLUDED.height_cm,
                            pot_size        = EXCLUDED.pot_size,
                            updated_at      = now()
                        WHERE product_snapshot.product_version < EXCLUDED.product_version
                        """.formatted(COLUMNS))
                .paramSource(snapshot)
                .update();
        return changed == 1;
    }

    public Optional<ProductSnapshot> findById(long productId) {
        return jdbcClient.sql("SELECT " + COLUMNS + " FROM product_snapshot WHERE product_id = :productId")
                .param("productId", productId)
                .query(ProductSnapshot.class)
                .optional();
    }

    /** 상품 문서를 만들 수 있는 상품 ID (payload v3 값이 있고 삭제되지 않음). 상품 재임베딩용 */
    public List<Long> findIdsWithDocument() {
        return jdbcClient.sql("""
                        SELECT product_id FROM product_snapshot
                        WHERE title IS NOT NULL AND status <> 'DELETED'
                        ORDER BY product_id
                        """)
                .query(Long.class)
                .list();
    }
}
