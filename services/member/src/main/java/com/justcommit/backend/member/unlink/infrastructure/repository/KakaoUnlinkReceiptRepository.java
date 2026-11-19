package com.justcommit.backend.member.unlink.infrastructure.repository;

import com.justcommit.backend.member.unlink.domain.KakaoUnlinkReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

// 카카오 연결 해제 웹훅 수신 기록 (#422). 처리용 조회 · 확보 쿼리는 처리 단계를 만들 때 추가
public interface KakaoUnlinkReceiptRepository extends JpaRepository<KakaoUnlinkReceipt, Long> {
}
