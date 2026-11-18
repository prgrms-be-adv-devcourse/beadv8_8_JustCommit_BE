package com.justcommit.backend.member.address.presentation;

import com.justcommit.backend.common.exception.GlobalExceptionHandler;
import com.justcommit.backend.common.security.AuthMember;
import com.justcommit.backend.member.address.application.AddressService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 배송지 수정 · 삭제 HTTP 계약 (#416): 요청 검증(@Valid)이 400 형식으로 나가는지, 삭제가 200 ApiResponse<Void>인지
@ExtendWith(MockitoExtension.class)
class AddressControllerTest {

  private static final Long MEMBER_ID = 10L;

  @Mock
  AddressService addressService;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.standaloneSetup(new AddressController(addressService))
        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
    SecurityContextHolder.getContext().setAuthentication(
        new UsernamePasswordAuthenticationToken(new AuthMember(MEMBER_ID, "MEMBER"), null, List.of()));
  }

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  @DisplayName("빈 PATCH 요청은 400 INVALID_FIELD_ERROR와 검증 메시지를 돌려주고 서비스를 부르지 않는다")
  void emptyPatch_badRequest() throws Exception {
    mockMvc.perform(patch("/api/v1/users/me/addresses/2")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("INVALID_FIELD_ERROR"))
        .andExpect(jsonPath("$.message").value(containsString("수정할 항목을 입력해주세요.")));

    verifyNoInteractions(addressService);
  }

  @Test
  @DisplayName("삭제에 성공하면 200과 메시지를 돌려주고(data 없음), 로그인한 회원 기준으로 삭제한다")
  void delete_ok() throws Exception {
    mockMvc.perform(delete("/api/v1/users/me/addresses/2"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(200))
        .andExpect(jsonPath("$.message").value("배송지를 삭제했습니다."))
        .andExpect(jsonPath("$.data").doesNotExist());

    verify(addressService).deleteAddress(MEMBER_ID, 2L);
  }
}
