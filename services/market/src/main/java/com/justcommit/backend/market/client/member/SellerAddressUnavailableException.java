package com.justcommit.backend.market.client.member;

import com.justcommit.backend.common.exception.BusinessException;
import com.justcommit.backend.common.exception.CommonErrorCode;

/**
 * 판매자 배송지를 사용할 수 없음 (복제본 없음 · 주소 암호문 없음 · 기본 주소 없음).
 * 원인은 이벤트 지연일 수도, 탈퇴로 주소가 지워진 것일 수도 있어 구분하지 않는다.
 * 응답으로 나가면 지금처럼 503이고, 택배 예약은 이 타입만 보류(warn)로 처리한다.
 */
public class SellerAddressUnavailableException extends BusinessException {

  public SellerAddressUnavailableException() {
    super(CommonErrorCode.INTERNAL_API_UNAVAILABLE);
  }
}
