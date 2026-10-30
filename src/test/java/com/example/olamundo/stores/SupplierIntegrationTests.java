package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class SupplierIntegrationTests extends StoreApiTestSupport {
	private static final String CNPJ_A = "11.222.333/0001-81";
	private static final String CPF_B = "529.982.247-25";

	@Test
	void everyMemberViewsButOnlyOwnerAndManagerWrite() throws Exception {
		var owner = actor();
		var manager = actor();
		var staff = actor();
		var outsider = actor();
		String store = createStore(owner, "Fornecedores Norte").get("id").asText();
		for (var member : List.of(manager, staff)) {
			assertThat(post(owner, "/api/stores/" + store + "/members", Map.of("email", member.account().email(),
					"role", member == manager ? "MANAGER" : "STAFF")).statusCode()).isEqualTo(201);
		}
		String path = "/api/stores/" + store + "/suppliers";
		var created = create(manager, path, "Moveis Fornecedor Ltda", CNPJ_A);
		String id = created.get("id").asText();
		assertThat(created.get("document").asText()).isEqualTo("11222333000181");
		assertThat(created.get("active").asBoolean()).isTrue();
		for (var member : List.of(owner, manager, staff)) {
			assertThat(get(member, path).statusCode()).isEqualTo(200);
			assertThat(get(member, path + "/" + id).statusCode()).isEqualTo(200);
		}
		assertThat(post(staff, path, Map.of("requestId", UUID.randomUUID(), "name", "Negado")).statusCode()).isEqualTo(403);
		assertThat(post(staff, path + "/" + id, edit(0, "Negado", CNPJ_A, true)).statusCode()).isEqualTo(403);
		var edited = post(owner, path + "/" + id, edit(0, "Fornecedor Editado", CNPJ_A, false));
		assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
		assertThat(json.readTree(edited.body()).get("active").asBoolean()).isFalse();
		assertThat(json.readTree(edited.body()).get("version").asLong()).isEqualTo(1);

		assertThat(get(outsider, path).statusCode()).isEqualTo(404);
		assertThat(post(outsider, path, Map.of("requestId", UUID.randomUUID(), "name", "Invasor")).statusCode()).isEqualTo(404);
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", "Sem CSRF"), false).statusCode()).isEqualTo(403);
		var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
	}

	@Test
	void suppliersBelongToTheCompanyNotToOneStore() throws Exception {
		var owner = actor();
		var otherOwner = actor();
		String north = createStore(owner, "Forn Empresa Norte").get("id").asText();
		String south = createStore(owner, "Forn Empresa Sul").get("id").asText();
		String foreign = createStore(otherOwner, "Forn Outra Empresa").get("id").asText();
		String id = create(owner, "/api/stores/" + north + "/suppliers", "Fornecedor Compartilhado", CNPJ_A).get("id").asText();
		assertThat(get(owner, "/api/stores/" + south + "/suppliers/" + id).statusCode()).isEqualTo(200);
		assertThat(get(otherOwner, "/api/stores/" + foreign + "/suppliers/" + id).statusCode()).isEqualTo(404);
		assertThat(get(otherOwner, "/api/stores/" + foreign + "/suppliers").body()).doesNotContain("Fornecedor Compartilhado");
		assertThat(create(otherOwner, "/api/stores/" + foreign + "/suppliers", "Mesmo CNPJ Outra Empresa", CNPJ_A)
				.get("id").asText()).isNotEqualTo(id);
		jdbc.sql("UPDATE stores SET owner_id = NULL WHERE id = :id").param("id", UUID.fromString(south)).update();
		assertThat(get(owner, "/api/stores/" + south + "/suppliers").statusCode()).isEqualTo(409);
	}

	@Test
	void validationDuplicateDocumentsVersionsAndReplay() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Forn Validacao").get("id").asText();
		String path = "/api/stores/" + store + "/suppliers";
		for (Map<String, Object> invalid : List.of(
				Map.<String, Object>of("name", "A"), Map.<String, Object>of("name", "Doc Ruim", "document", "12.345.678/0001-00"),
				Map.<String, Object>of("name", "Email Ruim", "email", "x"), Map.<String, Object>of("name", "Cep", "zip", "12"),
				Map.<String, Object>of("name", "Uf", "state", "BRA"), Map.<String, Object>of("name", "Fone", "phone", "abc"))) {
			var all = new HashMap<String, Object>(invalid);
			all.put("requestId", UUID.randomUUID());
			assertThat(post(owner, path, all).statusCode()).as(invalid.toString()).isEqualTo(400);
		}
		var first = create(owner, path, "Primeiro", CNPJ_A);
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", "Mesmo CNPJ", "document", CNPJ_A))
				.statusCode()).isEqualTo(409);
		var second = create(owner, path, "Segundo", CPF_B);
		assertThat(post(owner, path + "/" + second.get("id").asText(), edit(0, "Segundo", CNPJ_A, true)).statusCode()).isEqualTo(409);
		assertThat(create(owner, path, "Sem Doc A", null).get("document").isNull()).isTrue();
		assertThat(create(owner, path, "Sem Doc B", null).get("document").isNull()).isTrue();

		String item = path + "/" + first.get("id").asText();
		assertThat(post(owner, item, edit(0, "Primeiro Editado", CNPJ_A, true)).statusCode()).isEqualTo(200);
		assertThat(post(owner, item, edit(0, "Edicao Velha", CNPJ_A, true)).statusCode()).isEqualTo(409);
		assertThat(post(owner, path + "/" + UUID.randomUUID(), edit(0, "Fantasma", null, true)).statusCode()).isEqualTo(404);

		UUID request = UUID.randomUUID();
		var payload = Map.<String, Object>of("requestId", request, "name", "Replay");
		assertThat(post(owner, path, payload).statusCode()).isEqualTo(200);
		assertThat(post(owner, path, payload).statusCode()).isEqualTo(200);
		assertThat(post(owner, path, Map.of("requestId", request, "name", "Outro Nome")).statusCode()).isEqualTo(409);
	}

	@Test
	void searchByNameDocumentAndPhone() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Forn Busca").get("id").asText();
		String path = "/api/stores/" + store + "/suppliers";
		create(owner, path, "Madeireira Sul", CNPJ_A);
		var withPhone = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID(), "name", "Colchoes Norte",
				"document", CPF_B, "phone", "(41) 3797-8329"));
		assertThat(post(owner, path, withPhone).statusCode()).isEqualTo(200);
		assertThat(names(get(owner, path + "?q=madeireira"))).containsExactly("Madeireira Sul");
		assertThat(names(get(owner, path + "?q=11.222"))).containsExactly("Madeireira Sul");
		assertThat(names(get(owner, path + "?q=3797"))).containsExactly("Colchoes Norte");
		assertThat(names(get(owner, path + "?q=inexistente"))).isEmpty();
		assertThat(names(get(owner, path))).containsExactly("Colchoes Norte", "Madeireira Sul");
	}

	private JsonNode create(Actor actor, String path, String name, String document) throws Exception {
		var data = new HashMap<String, Object>();
		data.put("requestId", UUID.randomUUID());
		data.put("name", name);
		if (document != null) data.put("document", document);
		var response = post(actor, path, data);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private static Map<String, Object> edit(long version, String name, String document, boolean active) {
		var data = new HashMap<String, Object>();
		data.put("version", version);
		data.put("name", name);
		data.put("active", active);
		if (document != null) data.put("document", document);
		return data;
	}

	private List<String> names(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var names = new java.util.ArrayList<String>();
		json.readTree(response.body()).get("content").forEach(node -> names.add(node.get("name").asText()));
		return names;
	}
}
