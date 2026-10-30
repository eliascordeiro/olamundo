package com.example.olamundo.productgroups.infrastructure;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.example.olamundo.productgroups.application.ProductGroupRepository;
import com.example.olamundo.productgroups.domain.GroupData;
import com.example.olamundo.productgroups.domain.ProductGroup;
import com.example.olamundo.productgroups.domain.ProductSubgroup;
import com.example.olamundo.productgroups.domain.SubgroupData;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.simple.JdbcClient.StatementSpec;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProductGroupRepository implements ProductGroupRepository {
	private final JdbcClient jdbc;

	public JdbcProductGroupRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean createGroup(UUID ownerId, UUID id, GroupData data, String actorId, Instant now) {
		var statement = jdbc.sql("""
				INSERT INTO product_groups (id, owner_id, name, registration_name, ncm, commission, assembly_commission,
				assembly_discount, markup, pauta_discount, flexible_discount, created_by, created_at, updated_by, updated_at)
				VALUES (:id, :owner, :name, :name, :ncm, :commission, :assemblyCommission, :assemblyDiscount, :markup,
				:pautaDiscount, :flexibleDiscount, :actor, :now, :actor, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("owner", ownerId).param("actor", actorId).param("now", Timestamp.from(now));
		return bindGroup(statement, data).update() == 1;
	}

	@Override
	public boolean matchesGroupRegistration(UUID ownerId, UUID id, String name, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product_groups WHERE owner_id = :owner AND id = :id
				AND registration_name = :name AND created_by = :actor)
				""").param("owner", ownerId).param("id", id).param("name", name).param("actor", actorId)
				.query(Boolean.class).single();
	}

	@Override
	public Optional<ProductGroup> findGroup(UUID ownerId, UUID id) {
		return jdbc.sql("SELECT * FROM product_groups WHERE owner_id = :owner AND id = :id")
				.param("owner", ownerId).param("id", id).query(this::group).optional();
	}

	@Override
	public List<ProductGroup> listGroups(UUID ownerId) {
		return jdbc.sql("SELECT * FROM product_groups WHERE owner_id = :owner ORDER BY lower(name), id")
				.param("owner", ownerId).query(this::group).list();
	}

	@Override
	public Optional<ProductGroup> updateGroup(UUID ownerId, UUID id, GroupData data, long version, String actorId, Instant now) {
		var statement = jdbc.sql("""
				UPDATE product_groups SET name = :name, ncm = :ncm, commission = :commission,
				assembly_commission = :assemblyCommission, assembly_discount = :assemblyDiscount, markup = :markup,
				pauta_discount = :pautaDiscount, flexible_discount = :flexibleDiscount,
				version = version + 1, updated_by = :actor, updated_at = :now
				WHERE owner_id = :owner AND id = :id AND version = :version RETURNING *
				""").param("owner", ownerId).param("id", id).param("version", version)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bindGroup(statement, data).query(this::group).optional();
	}

	@Override
	public boolean createSubgroup(UUID ownerId, UUID groupId, UUID id, SubgroupData data, String actorId, Instant now) {
		var statement = jdbc.sql("""
				INSERT INTO product_subgroups (id, owner_id, group_id, name, registration_name, markup, margin,
				pauta_discount, flexible_discount, created_by, created_at, updated_by, updated_at)
				VALUES (:id, :owner, :group, :name, :name, :markup, :margin, :pautaDiscount, :flexibleDiscount,
				:actor, :now, :actor, :now)
				ON CONFLICT (id) DO NOTHING
				""").param("id", id).param("owner", ownerId).param("group", groupId)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bindSubgroup(statement, data).update() == 1;
	}

	@Override
	public boolean matchesSubgroupRegistration(UUID ownerId, UUID groupId, UUID id, String name, String actorId) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM product_subgroups WHERE owner_id = :owner AND group_id = :group AND id = :id
				AND registration_name = :name AND created_by = :actor)
				""").param("owner", ownerId).param("group", groupId).param("id", id).param("name", name)
				.param("actor", actorId).query(Boolean.class).single();
	}

	@Override
	public Optional<ProductSubgroup> findSubgroup(UUID ownerId, UUID groupId, UUID id) {
		return jdbc.sql("SELECT * FROM product_subgroups WHERE owner_id = :owner AND group_id = :group AND id = :id")
				.param("owner", ownerId).param("group", groupId).param("id", id).query(this::subgroup).optional();
	}

	@Override
	public List<ProductSubgroup> listSubgroups(UUID ownerId) {
		return jdbc.sql("SELECT * FROM product_subgroups WHERE owner_id = :owner ORDER BY group_id, lower(name), id")
				.param("owner", ownerId).query(this::subgroup).list();
	}

	@Override
	public Optional<ProductSubgroup> updateSubgroup(UUID ownerId, UUID groupId, UUID id, SubgroupData data, long version,
			String actorId, Instant now) {
		var statement = jdbc.sql("""
				UPDATE product_subgroups SET name = :name, markup = :markup, margin = :margin,
				pauta_discount = :pautaDiscount, flexible_discount = :flexibleDiscount,
				version = version + 1, updated_by = :actor, updated_at = :now
				WHERE owner_id = :owner AND group_id = :group AND id = :id AND version = :version RETURNING *
				""").param("owner", ownerId).param("group", groupId).param("id", id).param("version", version)
				.param("actor", actorId).param("now", Timestamp.from(now));
		return bindSubgroup(statement, data).query(this::subgroup).optional();
	}

	private static StatementSpec bindGroup(StatementSpec statement, GroupData data) {
		return statement.param("name", data.name()).param("ncm", data.ncm()).param("commission", data.commission())
				.param("assemblyCommission", data.assemblyCommission()).param("assemblyDiscount", data.assemblyDiscount())
				.param("markup", data.markup()).param("pautaDiscount", data.pautaDiscount())
				.param("flexibleDiscount", data.flexibleDiscount());
	}

	private static StatementSpec bindSubgroup(StatementSpec statement, SubgroupData data) {
		return statement.param("name", data.name()).param("markup", data.markup()).param("margin", data.margin())
				.param("pautaDiscount", data.pautaDiscount()).param("flexibleDiscount", data.flexibleDiscount());
	}

	private ProductGroup group(ResultSet row, int rowNum) throws SQLException {
		return new ProductGroup(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), row.getString("name"),
				row.getString("ncm"), row.getBigDecimal("commission"), row.getBigDecimal("assembly_commission"),
				row.getBigDecimal("assembly_discount"), row.getBigDecimal("markup"), row.getBigDecimal("pauta_discount"),
				row.getBigDecimal("flexible_discount"), row.getLong("version"), row.getString("created_by"),
				row.getTimestamp("created_at").toInstant(), row.getString("updated_by"),
				row.getTimestamp("updated_at").toInstant());
	}

	private ProductSubgroup subgroup(ResultSet row, int rowNum) throws SQLException {
		return new ProductSubgroup(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class),
				row.getObject("group_id", UUID.class), row.getString("name"), row.getBigDecimal("markup"),
				row.getBigDecimal("margin"), row.getBigDecimal("pauta_discount"), row.getBigDecimal("flexible_discount"),
				row.getLong("version"), row.getString("created_by"), row.getTimestamp("created_at").toInstant(),
				row.getString("updated_by"), row.getTimestamp("updated_at").toInstant());
	}
}
