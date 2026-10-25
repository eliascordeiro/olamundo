package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class StoreIsolationTests extends StoreApiTestSupport {
	@Test
	void storeMembershipsPersistAndNeverExposeAnotherTenantsStore() throws Exception {
		var owner = actor();
		var outsider = actor();
		String id = createStore(owner, "  Loja Norte  ").get("id").asText();
		String otherId = createStore(outsider, "Loja Sul").get("id").asText();

		var listing = json.readTree(get(owner, "/api/stores").body());
		assertThat(listing.get("content").size()).isEqualTo(1);
		assertThat(listing.get("content").get(0).get("name").asText()).isEqualTo("Loja Norte");
		assertThat(listing.get("content").get(0).get("role").asText()).isEqualTo("OWNER");
		assertThat(listing.toString()).doesNotContain(otherId);
		assertThat(get(owner, "/api/stores/" + id).statusCode()).isEqualTo(200);
		assertThat(get(outsider, "/api/stores/" + id).statusCode()).isEqualTo(404);
		assertThat(get(owner, "/api/stores/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
		assertThat(get(outsider, "/api/stores/" + id + "/members").statusCode()).isEqualTo(404);
		assertThat(post(outsider, "/api/stores/" + id + "/members",
				Map.of("email", outsider.account().email(), "role", "STAFF")).statusCode()).isEqualTo(404);

		var anonymous = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(uri("/api/stores")).GET().build(), HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
		assertThat(post(owner, "/api/stores", Map.of("name", "Sem CSRF"), false).statusCode()).isEqualTo(403);
		assertThat(get(owner, "/api/stores").body()).doesNotContain("Sem CSRF");

		assertThat(post(owner, "/api/auth/logout", Map.of()).statusCode()).isEqualTo(204);
		assertThat(get(owner, "/api/stores/" + id).statusCode()).isEqualTo(401);
	}

	@Test
	void listsAreBoundedAndInvalidInputDoesNotWriteStores() throws Exception {
		var owner = actor();
		createStore(owner, "Primeira");
		createStore(owner, "Segunda");
		var first = json.readTree(get(owner, "/api/stores?size=1").body());
		var next = json.readTree(get(owner, "/api/stores?page=1&size=1").body());
		assertThat(first.get("content").size()).isEqualTo(1);
		assertThat(first.get("hasNext").asBoolean()).isTrue();
		assertThat(next.get("hasNext").asBoolean()).isFalse();
		assertThat(first.get("content").get(0).get("id")).isNotEqualTo(next.get("content").get(0).get("id"));

		for (String query : new String[]{"?size=0", "?size=101", "?page=-1", "?page=invalid"}) {
			assertThat(get(owner, "/api/stores" + query).statusCode()).isEqualTo(400);
		}
		assertThat(get(owner, "/api/stores/not-a-uuid").statusCode()).isEqualTo(400);
		for (String name : new String[]{" ", " a ", "x".repeat(101)}) {
			assertThat(post(owner, "/api/stores", Map.of("name", name)).statusCode()).isEqualTo(400);
		}
		assertThat(json.readTree(get(owner, "/api/stores").body()).get("content").size()).isEqualTo(2);
	}
}
