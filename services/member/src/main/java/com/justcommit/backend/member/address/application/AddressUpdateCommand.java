package com.justcommit.backend.member.address.application;

// 배송지 부분 수정 입력값 (컨트롤러 -> 서비스)
// null = 그대로, 선택 항목(address2 · addressName)의 빈 문자열 = 지움. 해석은 Address.update()에서 한다
public record AddressUpdateCommand(
    String recipientName,
    String recipientPhone,
    String zipcode,
    String address1,
    String address2,
    String addressName
){
}
