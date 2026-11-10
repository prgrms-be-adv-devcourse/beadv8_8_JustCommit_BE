package com.justcommit.backend.product.client.member;

import com.justcommit.backend.product.member.domain.MemberReplica;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// 회원 복제본(member_replica)으로 판매자 여부 · 닉네임을 답한다. member를 직접 부르지 않음
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MemberReplicaQuery implements MemberQuery {

    private final MemberReplicaRepository memberReplicaRepository;

    // 복제본이 없거나(가입 이벤트가 아직 안 옴) 탈퇴했거나 SELLER가 아니면 false
    @Override
    public boolean isSeller(Long memberId) {
        return memberReplicaRepository.findById(memberId)
                .map(MemberReplica::isSeller)
                .orElse(false);
    }

    // 탈퇴한 회원은 "탈퇴한 회원", 복제본이 아직 없으면 null (상품 조회는 막지 않음)
    @Override
    public String getNickname(Long memberId) {
        return memberReplicaRepository.findById(memberId)
                .map(MemberReplica::displayNickname)
                .orElse(null);
    }
}
