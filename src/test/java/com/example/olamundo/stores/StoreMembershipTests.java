package com.example.olamundo.stores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.example.olamundo.stores.application.StoreService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

class StoreMembershipTests extends StoreApiTestSupport {
	@Autowired StoreService stores;

	@Test
	void oneUserCanHaveDifferentRolesInDifferentStoresWithoutEscalation() throws Exception {
		var owner = actor();
		var member = actor();
		String firstId = createStore(owner, "Loja A").get("id").asText();
		String secondId = createStore(owner, "Loja B").get("id").asText();
		assertThat(post(owner, "/api/stores/" + firstId + "/members",
				Map.of("email", member.account().email().toUpperCase(), "role", "MANAGER")).statusCode()).isEqualTo(201);
		assertThat(post(owner, "/api/stores/" + secondId + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(201);
		var listing = json.readTree(get(member, "/api/stores").body()).get("content");
		assertThat(listing.size()).isEqualTo(2);
		assertThat(get(member, "/api/stores/" + firstId).body()).contains("MANAGER");
		assertThat(get(member, "/api/stores/" + secondId).body()).contains("STAFF");
		assertThat(get(member, "/api/stores/" + firstId + "/members").statusCode()).isEqualTo(403);
		assertThat(post(member, "/api/stores/" + firstId + "/members",
				Map.of("email", member.account().email(), "role", "OWNER")).statusCode()).isEqualTo(403);
		assertThat(post(owner, "/api/stores/" + firstId + "/members",
				Map.of("email", member.account().email(), "role", "STAFF")).statusCode()).isEqualTo(409);
		assertThat(post(owner, "/api/stores/" + firstId + "/members",
				Map.of("email", member.account().email(), "role", "OWNER")).statusCode()).isEqualTo(400);
		assertThat(post(owner, "/api/stores/" + firstId + "/members",
				Map.of("email", member.account().email(), "role", "ADMIN")).statusCode()).isEqualTo(400);
		assertThat(post(owner, "/api/stores/" + firstId + "/members",
				Map.of("email", UUID.randomUUID() + "@example.com", "role", "STAFF")).statusCode()).isEqualTo(404);
		assertThat(json.readTree(get(owner, "/api/stores/" + firstId + "/members").body())
				.get("content").size()).isEqualTo(2);
	}

	@Test
	void storeAndOwnerAreCommittedAtomicallyAndMigrationsAreApplied() {
		String name = "rollback-" + UUID.randomUUID();
		UUID ownerRow = UUID.randomUUID();
		jdbc.sql("""
				INSERT INTO platform_owners (id, name, email, status, user_id, created_by, created_at, updated_at)
				VALUES (:id, 'Rollback', 'rollback@example.com', 'ACTIVE', 'rollback-user', 'test', now(), now())
				""").param("id", ownerRow).update();
		// Faz somente a insercao do vinculo OWNER falhar, depois de a loja ja ter sido inserida.
		jdbc.sql("ALTER TABLE store_memberships ADD CONSTRAINT test_reject_rollback_user CHECK (user_id <> 'rollback-user')").update();
		try {
			assertThatThrownBy(() -> stores.create("rollback-user", name)).isInstanceOf(DataIntegrityViolationException.class);
		} finally {
			jdbc.sql("ALTER TABLE store_memberships DROP CONSTRAINT test_reject_rollback_user").update();
			jdbc.sql("DELETE FROM platform_owners WHERE id = :id").param("id", ownerRow).update();
		}
		assertThat(jdbc.sql("SELECT count(*) FROM stores WHERE name = :name").param("name", name)
				.query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
				.query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void concurrentMembershipCreationHasOneWinnerWithoutChangingTheRole() throws Exception {
		var owner = actor();
		var member = actor();
		var id = UUID.fromString(createStore(owner, "Loja concorrente").get("id").asText());
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			var first = CompletableFuture.supplyAsync(() -> addMemberStatus(id, owner, member), executor);
			var second = CompletableFuture.supplyAsync(() -> addMemberStatus(id, owner, member), executor);
			assertThat(java.util.List.of(first.join(), second.join())).containsExactlyInAnyOrder(201, 409);
		}
		assertThat(json.readTree(get(owner, "/api/stores/" + id + "/members").body()).get("content").size())
				.isEqualTo(2);
		assertThat(get(member, "/api/stores/" + id).body()).contains("STAFF");
	}

	private int addMemberStatus(UUID id, Actor owner, Actor member) {
		try {
			stores.addMember(id, owner.account().id(), member.account().email(),
					com.example.olamundo.stores.domain.StoreRole.STAFF);
			return 201;
		} catch (ResponseStatusException exception) {
			return exception.getStatusCode().value();
		}
	}
}
