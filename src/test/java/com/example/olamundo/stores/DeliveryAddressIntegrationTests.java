package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class DeliveryAddressIntegrationTests extends StoreApiTestSupport {
	@Test
	void storeMembersManageDeliveryAddressesAndOutsidersGetNotFound() throws Exception {
		var owner = actor();
		var staff = actor();
		var outsider = actor();
		String store = createStore(owner, "Entrega Norte").get("id").asText();
		assertThat(post(owner, "/api/stores/" + store + "/members", Map.of("email", staff.account().email(), "role", "STAFF"))
				.statusCode()).isEqualTo(201);
		String customer = json.readTree(post(staff, "/api/stores/" + store + "/customers",
				Map.of("requestId", UUID.randomUUID(), "name", "Cliente Entrega")).body()).get("id").asText();
		String path = "/api/stores/" + store + "/customers/" + customer + "/delivery-addresses";

		UUID requestId = UUID.randomUUID();
		var body = Map.of("requestId", requestId, "zip", "83709-000", "street", "  Rua   das Flores ", "number", "10",
				"city", "Araucaria", "state", "pr");
		var created = post(staff, path, body);
		assertThat(created.statusCode()).as(created.body()).isEqualTo(200);
		var address = json.readTree(created.body());
		assertThat(address.get("zip").asText()).isEqualTo("83709000");
		assertThat(address.get("street").asText()).isEqualTo("Rua das Flores");
		assertThat(address.get("state").asText()).isEqualTo("PR");
		assertThat(json.readTree(post(staff, path, body).body()).get("id").asText()).isEqualTo(requestId.toString());
		assertThat(json.readTree(get(owner, path).body())).hasSize(1);

		String id = address.get("id").asText();
		var edit = Map.of("version", 0, "street", "Rua Nova", "city", "Curitiba");
		var edited = post(owner, path + "/" + id, edit);
		assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
		assertThat(json.readTree(edited.body()).get("version").asLong()).isEqualTo(1);
		assertThat(post(owner, path + "/" + id, edit).statusCode()).isEqualTo(409);

		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "street", "x")).statusCode()).isEqualTo(400);
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "street", "Rua Valida", "zip", "123")).statusCode()).isEqualTo(400);
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "street", "Rua Valida"), false).statusCode()).isEqualTo(403);

		assertThat(get(outsider, path).statusCode()).isEqualTo(404);
		assertThat(post(outsider, path, Map.of("requestId", UUID.randomUUID(), "street", "Rua Invasora")).statusCode()).isEqualTo(404);
		assertThat(get(owner, "/api/stores/" + store + "/customers/" + UUID.randomUUID() + "/delivery-addresses").statusCode()).isEqualTo(404);

		assertThat(delete(owner, path + "/" + id + "?version=0").statusCode()).isEqualTo(409);
		assertThat(delete(owner, path + "/" + id + "?version=1").statusCode()).isEqualTo(204);
		assertThat(json.readTree(get(owner, path).body())).isEmpty();
	}

	@Test
	void addressesOfOneCompanyAreInvisibleToAnotherEvenWithTheSameCustomerId() throws Exception {
		var owner = actor();
		var other = actor();
		String store = createStore(owner, "Empresa A").get("id").asText();
		String foreign = createStore(other, "Empresa B").get("id").asText();
		String customer = json.readTree(post(owner, "/api/stores/" + store + "/customers",
				Map.of("requestId", UUID.randomUUID(), "name", "Cliente A")).body()).get("id").asText();
		assertThat(post(owner, "/api/stores/" + store + "/customers/" + customer + "/delivery-addresses",
				Map.of("requestId", UUID.randomUUID(), "street", "Rua Secreta")).statusCode()).isEqualTo(200);
		assertThat(get(other, "/api/stores/" + foreign + "/customers/" + customer + "/delivery-addresses").statusCode()).isEqualTo(404);
	}
}
