package com.justcommit.backend.member.infrastructure.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class RefreshTokenRedisStoreTest {

  @Mock
  private StringRedisTemplate redisTemplate;

  @Mock
  private ValueOperations<String, String> valueOperations;

  @InjectMocks
  private RefreshTokenRedisStore store;

  // SHA-256("abc")는 표준 테스트 벡터라 값이 고정되어 있음
  private static final String RAW_TOKEN = "abc";
  private static final String SHA256_OF_ABC =
          "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

  @Test
  @DisplayName("저장: 토큰 원문이 아니라 SHA-256 해시를 키로, 회원 id를 값으로, TTL과 함께 저장")
  void save_hashedKey() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    Duration ttl = Duration.ofHours(3);

    store.save(RAW_TOKEN, 4L, ttl);

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    then(valueOperations).should().set(keyCaptor.capture(), eq("4"), eq(ttl));
    assertThat(keyCaptor.getValue()).isEqualTo("auth:refresh:" + SHA256_OF_ABC);
  }
}
