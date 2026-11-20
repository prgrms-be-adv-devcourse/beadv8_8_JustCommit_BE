package com.justcommit.backend.payment.application.member;

import com.justcommit.backend.payment.infrastructure.repository.MemberReplicaRepository;
import com.justcommit.backend.payment.infrastructure.repository.WalletRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

// 회원 이벤트를 복제본과 지갑에 반영한다. 더 오래된 이벤트는 저장소의 조건부 upsert가 무시한다
// 탈퇴 회원의 지갑 처리는 #397 (여기서는 복제본 탈퇴 표시만)
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberReplicaService {

    private final MemberReplicaRepository memberReplicaRepository;
    private final WalletRepository walletRepository;

    // 가입: 복제본 반영 + 회원 지갑 생성 (이미 있으면 그대로). 같은 이벤트가 두 번 와도 지갑은 하나
    @Transactional
    public void applyCreated(Long memberId, String nickname, String role, boolean seller, OffsetDateTime occurredAt) {
        applyChanged(memberId, nickname, role, seller, occurredAt);
        if (walletRepository.insertMemberWalletIfAbsent(memberId) == 0) {
            log.debug("회원 지갑이 이미 있어 그대로 둠: memberId={}", memberId);
        }
    }

    // 정보 변경 (바뀐 뒤 전체 값)
    @Transactional
    public void applyChanged(Long memberId, String nickname, String role, boolean seller, OffsetDateTime occurredAt) {
        int changed = memberReplicaRepository.upsertMember(memberId, nickname, role, seller, occurredAt);
        if (changed == 0) {
            log.debug("이미 더 최신 값이 있어 회원 이벤트를 무시함: memberId={}, occurredAt={}", memberId, occurredAt);
        }
    }

    // 탈퇴: 복제본이 없어도 탈퇴 표시 행을 남김
    @Transactional
    public void applyWithdrawn(Long memberId, OffsetDateTime occurredAt) {
        int changed = memberReplicaRepository.markWithdrawn(memberId, occurredAt);
        if (changed == 0) {
            log.debug("이미 더 최신 값이 있어 탈퇴 이벤트를 무시함: memberId={}, occurredAt={}", memberId, occurredAt);
        }
    }
}
