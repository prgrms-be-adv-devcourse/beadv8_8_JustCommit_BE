package com.justcommit.backend.product.client.member;

import com.justcommit.backend.product.member.domain.MemberReplica;
import com.justcommit.backend.product.member.infrastructure.MemberReplicaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;

// 회원 복제본(member_replica)으로 판매자 여부 · 닉네임을 답한다. member를 직접 부르지 않음
@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MemberReplicaQuery implements MemberQuery {

    private final MemberReplicaRepository memberReplicaRepository;

    // 복제본이 없거나(가입 이벤트가 아직 안 옴) 탈퇴했거나 판매자 등록(seller)을 안 했으면 false
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

    // findAllById → WHERE member_id IN (...) 한 번. 없는 회원은 빠지고, 기본 이름은 부르는 쪽이 정함
    @Override
    public Map<Long, String> getNicknames(Collection<Long> memberIds) {
        if (memberIds.isEmpty()) {
            return Map.of();
        }
        // toMap은 null 값을 받지 않음 → 닉네임이 비어 있는 행은 「없는 회원」과 같이 다룸
        return memberReplicaRepository.findAllById(memberIds).stream()
                .filter(replica -> replica.displayNickname() != null)
                .collect(Collectors.toMap(MemberReplica::getMemberId, MemberReplica::displayNickname));
    }
}
