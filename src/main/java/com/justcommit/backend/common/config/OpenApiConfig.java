package com.justcommit.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  // Swagger 안에서 쓰는 인증 방식 이름(등록과 적용에 같은 이름을 써야 연결됨)
  private static final String BEARER_AUTH = "bearerAuth";

  @Bean
  public OpenAPI openAPI() {
    // 인증방식 정의: HTTP Bearer + JWT
    SecurityScheme bearerScheme = new SecurityScheme()
            .type(SecurityScheme.Type.HTTP)
            .scheme("bearer")
            .bearerFormat("JWT");

    return new OpenAPI()
            .info(new Info().title("RePlant Market API").version("v1"))
            .components(new Components().addSecuritySchemes(BEARER_AUTH, bearerScheme))
            // 모든 API에 적용 (Authorize에 넣은 토큰이 모든 요청에 붙음)
            .addSecurityItem(new SecurityRequirement().addList(BEARER_AUTH));
  }
}
