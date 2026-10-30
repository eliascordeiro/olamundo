package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class CustomerIntegrationTests extends StoreApiTestSupport {
	private static final String CPF_A = "529.982.247-25";
	private static final String CPF_B = "111.444.777-35";
	private static final String CNPJ = "11.222.333/0001-81";

	@Test
	void everyStoreMemberViewsCreatesAndEditsAndOutsidersGetNotFound() throws Exception {
		var owner = actor();
		var manager = actor();
		var staff = actor();
		var outsider = actor();
		String store = createStore(owner, "Clientes Norte").get("id").asText();
		for (var member : java.util.List.of(manager, staff)) {
			assertThat(post(owner, "/api/stores/" + store + "/members", Map.of("email", member.account().email(),
					"role", member == manager ? "MANAGER" : "STAFF")).statusCode()).isEqualTo(201);
		}
		String path = "/api/stores/" + store + "/customers";
		var created = create(staff, path, "Maria Souza", CPF_A);
		assertThat(created.get("document").asText()).isEqualTo("52998224725");
		assertThat(created.get("createdBy").asText()).isEqualTo(staff.account().id());
		for (var member : java.util.List.of(owner, manager, staff)) {
			assertThat(get(member, path).statusCode()).isEqualTo(200);
			assertThat(get(member, path + "/" + created.get("id").asText()).statusCode()).isEqualTo(200);
		}
		var edited = post(manager, path + "/" + created.get("id").asText(), body(Map.of("version", 0, "name", "Maria de Souza",
				"document", CPF_A, "city", "Curitiba", "state", "pr")));
		assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
		var editedJson = json.readTree(edited.body());
		assertThat(editedJson.get("version").asLong()).isEqualTo(1);
		assertThat(editedJson.get("state").asText()).isEqualTo("PR");
		assertThat(editedJson.get("updatedBy").asText()).isEqualTo(manager.account().id());

		assertThat(get(outsider, path).statusCode()).isEqualTo(404);
		assertThat(get(outsider, path + "/" + created.get("id").asText()).statusCode()).isEqualTo(404);
		assertThat(post(outsider, path, body(Map.of("requestId", UUID.randomUUID(), "name", "Invasor"))).statusCode()).isEqualTo(404);
		assertThat(post(owner, path, body(Map.of("requestId", UUID.randomUUID(), "name", "Sem CSRF")), false).statusCode()).isEqualTo(403);
		var anonymous = HttpClient.newHttpClient().send(HttpRequest.newBuilder(uri(path)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(anonymous.statusCode()).isEqualTo(401);
	}

	@Test
	void customersBelongToTheCompanySoAllItsStoresShareThemAndOtherCompaniesNeverSeeThem() throws Exception {
		var owner = actor();
		var otherOwner = actor();
		String north = createStore(owner, "Empresa Norte").get("id").asText();
		String south = createStore(owner, "Empresa Sul").get("id").asText();
		String foreign = createStore(otherOwner, "Outra Empresa").get("id").asText();
		var created = create(owner, "/api/stores/" + north + "/customers", "Cliente Compartilhado", CPF_A);
		String id = created.get("id").asText();
		assertThat(get(owner, "/api/stores/" + south + "/customers/" + id).statusCode()).isEqualTo(200);
		assertThat(get(owner, "/api/stores/" + south + "/customers").body()).contains("Cliente Compartilhado");
		assertThat(get(otherOwner, "/api/stores/" + foreign + "/customers/" + id).statusCode()).isEqualTo(404);
		assertThat(get(otherOwner, "/api/stores/" + foreign + "/customers").body()).doesNotContain("Cliente Compartilhado");
		assertThat(create(otherOwner, "/api/stores/" + foreign + "/customers", "Mesmo CPF Outra Empresa", CPF_A)
				.get("id").asText()).isNotEqualTo(id);

		jdbc.sql("UPDATE stores SET owner_id = NULL WHERE id = :id").param("id", UUID.fromString(south)).update();
		assertThat(get(owner, "/api/stores/" + south + "/customers").statusCode()).isEqualTo(409);
	}

	@Test
	void validationNormalizationAndDuplicateDocuments() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Validacao").get("id").asText();
		String path = "/api/stores/" + store + "/customers";
		for (Map<String, Object> invalid : java.util.List.of(
				Map.<String, Object>of("name", "A"),
				Map.<String, Object>of("name", "Cpf Ruim", "document", "123.456.789-00"),
				Map.<String, Object>of("name", "Cpf Repetido", "document", "111.111.111-11"),
				Map.<String, Object>of("name", "Email Ruim", "email", "sem-arroba"),
				Map.<String, Object>of("name", "Cep Curto", "zip", "8277"),
				Map.<String, Object>of("name", "Uf Longa", "state", "PRX"),
				Map.<String, Object>of("name", "Futuro", "birthDate", "2999-01-01"),
				Map.<String, Object>of("name", "Fone Ruim", "phone", "abc"))) {
			var all = new HashMap<String, Object>(invalid);
			all.put("requestId", UUID.randomUUID());
			assertThat(post(owner, path, body(all)).statusCode()).as(invalid.toString()).isEqualTo(400);
		}
		var full = new HashMap<String, Object>();
		full.put("requestId", UUID.randomUUID());
		full.put("name", "  Joao   da   Silva ");
		full.put("document", CNPJ);
		full.put("email", " Joao@Example.COM ");
		full.put("zip", "82.770-000");
		full.put("birthDate", "1980-05-17");
		full.put("phone", "(41) 3797-8329");
		var response = post(owner, path, body(full));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var saved = json.readTree(response.body());
		assertThat(saved.get("name").asText()).isEqualTo("Joao da Silva");
		assertThat(saved.get("document").asText()).isEqualTo("11222333000181");
		assertThat(saved.get("email").asText()).isEqualTo("joao@example.com");
		assertThat(saved.get("zip").asText()).isEqualTo("82770000");
		assertThat(saved.get("birthDate").asText()).isEqualTo("1980-05-17");

		assertThat(post(owner, path, body(Map.of("requestId", UUID.randomUUID(), "name", "Outro", "document", CNPJ)))
				.statusCode()).isEqualTo(409);
		var second = create(owner, path, "Segundo", CPF_B);
		assertThat(post(owner, path + "/" + second.get("id").asText(), body(Map.of("version", 0, "name", "Segundo",
				"document", CNPJ))).statusCode()).isEqualTo(409);
		var noDocumentA = create(owner, path, "Sem Documento A", null);
		var noDocumentB = create(owner, path, "Sem Documento B", null);
		assertThat(noDocumentA.get("document").isNull()).isTrue();
		assertThat(noDocumentB.get("id").asText()).isNotEqualTo(noDocumentA.get("id").asText());
	}

	@Test
	void registrationReplayAndVersionedEdits() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Replay").get("id").asText();
		String path = "/api/stores/" + store + "/customers";
		UUID request = UUID.randomUUID();
		var payload = body(Map.of("requestId", request, "name", "Replay Cliente"));
		var first = post(owner, path, payload);
		var again = post(owner, path, payload);
		assertThat(first.statusCode()).isEqualTo(200);
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(json.readTree(again.body()).get("id").asText()).isEqualTo(request.toString());
		assertThat(post(owner, path, body(Map.of("requestId", request, "name", "Nome Diferente"))).statusCode()).isEqualTo(409);

		String item = path + "/" + request;
		assertThat(post(owner, item, body(Map.of("version", 0, "name", "Primeira Edicao"))).statusCode()).isEqualTo(200);
		assertThat(post(owner, item, body(Map.of("version", 0, "name", "Edicao Velha"))).statusCode()).isEqualTo(409);
		assertThat(json.readTree(get(owner, item).body()).get("name").asText()).isEqualTo("Primeira Edicao");
		assertThat(post(owner, path + "/" + UUID.randomUUID(), body(Map.of("version", 0, "name", "Fantasma"))).statusCode())
				.isEqualTo(404);
		assertThat(post(owner, item, body(Map.of("name", "Sem versao"))).statusCode()).isEqualTo(400);
	}

	@Test
	void searchByNameDocumentAndPhoneIsPagedAndEscapesWildcards() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Busca").get("id").asText();
		String path = "/api/stores/" + store + "/customers";
		create(owner, path, "Ana Paula Lima", CPF_A);
		var withPhone = new HashMap<String, Object>(Map.of("requestId", UUID.randomUUID(), "name", "Bruno Costa",
				"document", CPF_B, "phone", "(41) 3797-8329", "mobile", "41 99999-1234"));
		assertThat(post(owner, path, body(withPhone)).statusCode()).isEqualTo(200);
		create(owner, path, "100% Promocao", null);
		for (int i = 0; i < 21; i++) {
			create(owner, path, "Carlos Numero " + String.format("%02d", i), null);
		}

		assertThat(names(get(owner, path + "?q=paula"))).containsExactly("Ana Paula Lima");
		assertThat(names(get(owner, path + "?q=LIMA"))).containsExactly("Ana Paula Lima");
		assertThat(names(get(owner, path + "?q=111.444"))).containsExactly("Bruno Costa");
		assertThat(names(get(owner, path + "?q=3797"))).containsExactly("Bruno Costa");
		assertThat(names(get(owner, path + "?q=99999-1234"))).containsExactly("Bruno Costa");
		assertThat(names(get(owner, path + "?q=%25"))).containsExactly("100% Promocao");
		assertThat(names(get(owner, path + "?q=inexistente"))).isEmpty();

		var firstPage = json.readTree(get(owner, path + "?size=20").body());
		assertThat(firstPage.get("content")).hasSize(20);
		assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
		assertThat(json.readTree(get(owner, path + "?size=20&page=1").body()).get("content")).hasSize(4);
		assertThat(get(owner, path + "?size=101").statusCode()).isEqualTo(400);
	}

	private JsonNode create(Actor actor, String path, String name, String document) throws Exception {
		var data = new HashMap<String, Object>();
		data.put("requestId", UUID.randomUUID());
		data.put("name", name);
		if (document != null) data.put("document", document);
		var response = post(actor, path, body(data));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}

	private java.util.List<String> names(HttpResponse<String> response) throws Exception {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		var names = new java.util.ArrayList<String>();
		json.readTree(response.body()).get("content").forEach(node -> names.add(node.get("name").asText()));
		return names;
	}

	private static Map<String, Object> body(Map<String, Object> values) {
		return values;
	}
}
