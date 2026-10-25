package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StoreDocumentationTests extends StoreApiTestSupport {
	@Test
	void swaggerRequiresLoginAndDescribesTenantRoutesAndCsrf() throws Exception {
		var anonymous = HttpClient.newHttpClient();
		for (String path : new String[]{"/api/openapi", "/api/docs", "/api/swagger-ui/index.html"}) {
			assertThat(anonymous.send(HttpRequest.newBuilder(uri(path)).GET().build(),
					HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
		}
		var owner = actor();
		var openApi = get(owner, "/api/openapi");
		assertThat(openApi.statusCode()).as(openApi.body()).isEqualTo(200);
		var spec = json.readTree(openApi.body());
		assertThat(spec.get("paths").has("/api/stores/{storeId}/members")).isTrue();
		assertThat(spec.get("paths").has("/api/stores/{storeId}/products/{productId}")).isTrue();
		assertThat(spec.get("paths").has("/api/stores/{storeId}/products/{productId}/stock")).isTrue();
		assertThat(spec.get("paths").has("/api/stores/{storeId}/orders/{orderId}")).isTrue();
		assertThat(spec.get("components").get("securitySchemes").get("csrf").get("name").asText())
				.isEqualTo("X-CSRF-TOKEN");
		assertThat(spec.get("servers").get(0).get("url").asText()).isEqualTo("/");
		for (String path : new String[]{"/api/stores", "/api/stores/{storeId}/members",
				"/api/auth/login", "/api/auth/register", "/api/mensagens", "/api/stores/{storeId}/products",
				"/api/stores/{storeId}/products/{productId}/stock/movements", "/api/stores/{storeId}/orders",
				"/api/stores/{storeId}/orders/{orderId}/confirm"}) {
			assertThat(spec.get("paths").get(path).get("post").get("security").toString()).contains("csrf");
		}
		assertThat(get(owner, "/api/openapi/swagger-config").body()).contains("/api/openapi");
		var docs = get(owner, "/api/docs");
		assertThat(docs.statusCode()).isEqualTo(302);
		String uiPath = URI.create(docs.headers().firstValue("location").orElseThrow()).getPath();
		assertThat(uiPath).isEqualTo("/api/swagger-ui/index.html");
		var ui = get(owner, uiPath);
		assertThat(ui.statusCode()).isEqualTo(200);
		assertThat(ui.headers().firstValue("content-security-policy").orElseThrow()).contains("script-src 'self'");
		assertThat(get(owner, "/api/swagger-ui/swagger-ui-bundle.js").statusCode()).isEqualTo(200);
		String id = createStore(owner, "Loja pelo contrato documentado").get("id").asText();
		assertThat(get(owner, "/api/stores/" + id).statusCode()).isEqualTo(200);
		assertThat(post(owner, "/api/auth/logout", Map.of()).statusCode()).isEqualTo(204);
		assertThat(get(owner, "/api/openapi").statusCode()).isEqualTo(401);
	}

	@Test
	void readinessChecksDatabasesWithoutExposingCredentials() throws Exception {
		var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri("/api/health")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).doesNotContain("components", "details", "jdbc", "mongo", "password");
		assertThat(json.readTree(response.body()).get("status").asText()).isEqualTo("UP");
	}
}
