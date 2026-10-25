package com.example.olamundo.config;

import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name = "app.api-docs.enabled", havingValue = "true")
public class OpenApiConfiguration {
	@Bean
	OpenAPI platformApi() {
		return new OpenAPI()
				.info(new Info().title("Nexo - Plataforma multiloja").version("1")
						.description("Etapa 1: lojas e vinculos. Entre pelo frontend na mesma origem. "
								+ "Obtenha token em GET /api/auth/csrf e informe-o em Authorize antes de POSTs. "
								+ "O token muda apos login; nao use contas ou dados reais em testes."))
				.servers(List.of(new Server().url("/")))
				.components(new Components().addSecuritySchemes("csrf", new SecurityScheme()
						.type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-CSRF-TOKEN")
						.description("Token retornado por GET /api/auth/csrf na sessao atual.")));
	}
}
