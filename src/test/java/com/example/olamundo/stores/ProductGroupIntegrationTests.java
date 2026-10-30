package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class ProductGroupIntegrationTests extends StoreApiTestSupport {

	@Test
	void everyMemberListsButOnlyOwnerAndManagerWrite() throws Exception {
		var owner = actor();
		var manager = actor();
		var staff = actor();
		var outsider = actor();
		String store = createStore(owner, "Grupos Norte").get("id").asText();
		for (var member : List.of(manager, staff)) {
			assertThat(post(owner, "/api/stores/" + store + "/members", Map.of("email", member.account().email(),
					"role", member == manager ? "MANAGER" : "STAFF")).statusCode()).isEqualTo(201);
		}
		String path = "/api/stores/" + store + "/product-groups";
		assertThat(json.readTree(get(staff, path).body())).isEmpty();
		var group = createGroup(manager, path, "SOFA", Map.of("ncm", "9401.61.00", "commission", 1.5, "markup", 130));
		assertThat(group.get("ncm").asText()).isEqualTo("9401.61.00");
		assertThat(group.get("commission").decimalValue().doubleValue()).isEqualTo(1.5);
		assertThat(group.get("markup").decimalValue().doubleValue()).isEqualTo(130.0);
		assertThat(group.get("pautaDiscount").decimalValue().doubleValue()).isZero();
		assertThat(group.get("subgroups")).isEmpty();

		for (var member : List.of(owner, manager, staff)) {
			assertThat(get(member, path).statusCode()).isEqualTo(200);
		}
		assertThat(post(staff, path, Map.of("requestId", UUID.randomUUID(), "name", "Negado")).statusCode()).isEqualTo(403);
		String groupId = group.get("id").asText();
		assertThat(post(staff, path + "/" + groupId, Map.of("version", 0, "name", "Negado")).statusCode()).isEqualTo(403);
		assertThat(post(staff, path + "/" + groupId + "/subgroups", Map.of("requestId", UUID.randomUUID(), "name", "Negado"))
				.statusCode()).isEqualTo(403);
		assertThat(get(outsider, path).statusCode()).isEqualTo(404);
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", "Sem CSRF"), false).statusCode()).isEqualTo(403);
	}

	@Test
	void groupsAndSubgroupsBelongToTheCompanyAndNestInTheListing() throws Exception {
		var owner = actor();
		var otherOwner = actor();
		String north = createStore(owner, "Grupos Empresa Norte").get("id").asText();
		String south = createStore(owner, "Grupos Empresa Sul").get("id").asText();
		String foreign = createStore(otherOwner, "Grupos Outra Empresa").get("id").asText();
		String northPath = "/api/stores/" + north + "/product-groups";
		var group = createGroup(owner, northPath, "ELETRO", Map.of());
		String groupId = group.get("id").asText();
		var sub = post(owner, northPath + "/" + groupId + "/subgroups",
				Map.of("requestId", UUID.randomUUID(), "name", "GELADEIRA", "markup", 40, "margin", 25.5, "flexibleDiscount", 10));
		assertThat(sub.statusCode()).as(sub.body()).isEqualTo(200);
		var subJson = json.readTree(sub.body());
		assertThat(subJson.get("groupId").asText()).isEqualTo(groupId);
		assertThat(subJson.get("margin").decimalValue().doubleValue()).isEqualTo(25.5);

		var southView = json.readTree(get(owner, "/api/stores/" + south + "/product-groups").body());
		assertThat(southView).hasSize(1);
		assertThat(southView.get(0).get("subgroups")).hasSize(1);
		assertThat(southView.get(0).get("subgroups").get(0).get("name").asText()).isEqualTo("GELADEIRA");
		assertThat(json.readTree(get(otherOwner, "/api/stores/" + foreign + "/product-groups").body())).isEmpty();
		assertThat(post(otherOwner, "/api/stores/" + foreign + "/product-groups/" + groupId + "/subgroups",
				Map.of("requestId", UUID.randomUUID(), "name", "Invasao")).statusCode()).isEqualTo(404);
		assertThat(createGroup(otherOwner, "/api/stores/" + foreign + "/product-groups", "ELETRO", Map.of()).get("id").asText())
				.isNotEqualTo(groupId);
	}

	@Test
	void validationDuplicatesVersionsAndReplay() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Grupos Validacao").get("id").asText();
		String path = "/api/stores/" + store + "/product-groups";
		for (Map<String, Object> invalid : List.of(
				Map.<String, Object>of("name", "A"), Map.<String, Object>of("name", "Comissao Alta", "commission", 101),
				Map.<String, Object>of("name", "Casas", "markup", 1.234), Map.<String, Object>of("name", "Negativo", "commission", -1),
				Map.<String, Object>of("name", "Markup Enorme", "markup", 1000.01))) {
			var all = new HashMap<String, Object>(invalid);
			all.put("requestId", UUID.randomUUID());
			assertThat(post(owner, path, all).statusCode()).as(invalid.toString()).isEqualTo(400);
		}
		var group = createGroup(owner, path, "Colchoes", Map.of());
		assertThat(post(owner, path, Map.of("requestId", UUID.randomUUID(), "name", "  colchoes ")).statusCode()).isEqualTo(409);

		String item = path + "/" + group.get("id").asText();
		assertThat(post(owner, item, Map.of("version", 0, "name", "Colchoes e Box", "commission", 2)).statusCode()).isEqualTo(200);
		assertThat(post(owner, item, Map.of("version", 0, "name", "Edicao Velha")).statusCode()).isEqualTo(409);
		var other = createGroup(owner, path, "Sofas", Map.of());
		assertThat(post(owner, path + "/" + other.get("id").asText(), Map.of("version", 0, "name", "COLCHOES E BOX"))
				.statusCode()).isEqualTo(409);
		assertThat(post(owner, path + "/" + UUID.randomUUID(), Map.of("version", 0, "name", "Fantasma")).statusCode()).isEqualTo(404);

		UUID request = UUID.randomUUID();
		var payload = Map.<String, Object>of("requestId", request, "name", "Replay");
		assertThat(post(owner, path, payload).statusCode()).isEqualTo(200);
		assertThat(post(owner, path, payload).statusCode()).isEqualTo(200);
		assertThat(post(owner, path, Map.of("requestId", request, "name", "Outro")).statusCode()).isEqualTo(409);

		String sub = item + "/subgroups";
		UUID subRequest = UUID.randomUUID();
		var subPayload = Map.<String, Object>of("requestId", subRequest, "name", "Casal");
		assertThat(post(owner, sub, subPayload).statusCode()).isEqualTo(200);
		assertThat(post(owner, sub, subPayload).statusCode()).isEqualTo(200);
		assertThat(post(owner, sub + "/" + subRequest, Map.of("version", 0, "name", "Casal King", "markup", 35)).statusCode()).isEqualTo(200);
		assertThat(post(owner, sub + "/" + subRequest, Map.of("version", 0, "name", "Velha")).statusCode()).isEqualTo(409);
		assertThat(post(owner, sub + "/" + UUID.randomUUID(), Map.of("version", 0, "name", "Fantasma")).statusCode()).isEqualTo(404);
		assertThat(post(owner, path + "/" + UUID.randomUUID() + "/subgroups", Map.of("requestId", UUID.randomUUID(), "name", "Orfao"))
				.statusCode()).isEqualTo(404);
	}

	@Test
	void productsExposeGroupAndSubgroupNames() throws Exception {
		var owner = actor();
		String store = createStore(owner, "Grupos Produto").get("id").asText();
		String path = "/api/stores/" + store + "/product-groups";
		var group = createGroup(owner, path, "DECORACOES", Map.of());
		String groupId = group.get("id").asText();
		String subId = json.readTree(post(owner, path + "/" + groupId + "/subgroups",
				Map.of("requestId", UUID.randomUUID(), "name", "QUADROS")).body()).get("id").asText();
		var product = post(owner, "/api/stores/" + store + "/products", Map.of("name", "Quadro Grande", "sku", "QD-1", "price", "99.90"));
		assertThat(product.statusCode()).as(product.body()).isEqualTo(201);
		String productId = json.readTree(product.body()).get("id").asText();
		assertThat(json.readTree(get(owner, "/api/stores/" + store + "/products/" + productId).body()).get("groupName").isNull()).isTrue();
		jdbc.sql("UPDATE catalog_products SET group_id = :g, subgroup_id = :s WHERE id = :id")
				.param("g", UUID.fromString(groupId)).param("s", UUID.fromString(subId)).param("id", UUID.fromString(productId)).update();
		var linked = json.readTree(get(owner, "/api/stores/" + store + "/products/" + productId).body());
		assertThat(linked.get("groupName").asText()).isEqualTo("DECORACOES");
		assertThat(linked.get("subgroupName").asText()).isEqualTo("QUADROS");
		assertThat(json.readTree(get(owner, "/api/stores/" + store + "/products").body()).get("content").get(0).get("groupName").asText())
				.isEqualTo("DECORACOES");
	}

	private JsonNode createGroup(Actor actor, String path, String name, Map<String, Object> extra) throws Exception {
		var data = new HashMap<String, Object>(extra);
		data.put("requestId", UUID.randomUUID());
		data.put("name", name);
		var response = post(actor, path, data);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return json.readTree(response.body());
	}
}
