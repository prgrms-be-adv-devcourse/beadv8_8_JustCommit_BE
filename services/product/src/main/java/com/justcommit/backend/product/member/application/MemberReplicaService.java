package com.justcommit.backend.product.member.application;

import com.justcommit.backend.product.member.domain.MemberRole;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import com.justcommit.backend.product.product.infrastructure.ProductRepository;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// member.events.v1을 회원 복제본과 상품에 반영 (docs/services/product.md 「구독 이벤트」)
// 모든 처리가 멱등이라 같은 이벤트가 다시 와도 결과가 같다
@Service
@RequiredArgsConstructor
public class MemberReplicaService {

    private final MemberReplicaRepository memberReplicaRepository;
    private final ProductRepository productRepository;

    /**
     * 가입 · 정보 변경을 복제본에 반영한다.
     *
     * @return 반영했으면 true, 오래된 이벤트 · 중복 · 이미 탈퇴한 회원이면 false (정상)
     */
    @Transactional
    public boolean applyProfile(Long memberId, String nickname, MemberRole role, OffsetDateTime occurredAt) {
        return memberReplicaRepository.upsertProfile(memberId, nickname, role.name(), occurredAt) > 0;
    }

    /**
     * 탈퇴를 반영한다. 복제본을 탈퇴 상태로 두고, 그 판매자의 판매 중 상품을 삭제한다.
     *
     * @return 삭제한 상품 수 (0이어도 정상)
     */
    @Transactional
    public int applyWithdrawn(Long memberId, OffsetDateTime occurredAt) {
        memberReplicaRepository.upsertWithdrawn(memberId, occurredAt);
        return productRepository.deleteOnSaleBySeller(memberId);
    }
}
