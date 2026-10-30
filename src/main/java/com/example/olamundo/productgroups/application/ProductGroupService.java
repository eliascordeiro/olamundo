package com.example.olamundo.productgroups.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.example.olamundo.productgroups.domain.GroupData;
import com.example.olamundo.productgroups.domain.ProductGroup;
import com.example.olamundo.productgroups.domain.ProductSubgroup;
import com.example.olamundo.productgroups.domain.SubgroupData;
import com.example.olamundo.shared.Fields;
import com.example.olamundo.stores.application.CompanyAccess;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Grupos e subgrupos de produtos pertencem a empresa e valem para todas as lojas dela.
 * Qualquer membro consulta; somente OWNER e MANAGER cadastram e editam.
 */
@Service
public class ProductGroupService {
	private static final String WRITER_MESSAGE = "Somente OWNER ou MANAGER pode cadastrar ou editar grupos de produtos.";

	private final ProductGroupRepository groups;
	private final CompanyAccess company;

	public ProductGroupService(ProductGroupRepository groups, CompanyAccess company) {
		this.groups = groups;
		this.company = company;
	}

	public record GroupWithSubgroups(ProductGroup group, List<ProductSubgroup> subgroups) {
	}

	@Transactional(readOnly = true)
	public List<GroupWithSubgroups> list(UUID storeId, String actorId) {
		UUID ownerId = company.companyOf(storeId, actorId);
		Map<UUID, List<ProductSubgroup>> byGroup = groups.listSubgroups(ownerId).stream()
				.collect(Collectors.groupingBy(ProductSubgroup::groupId));
		return groups.listGroups(ownerId).stream()
				.map(group -> new GroupWithSubgroups(group, byGroup.getOrDefault(group.id(), List.of()))).toList();
	}

	@Transactional
	public GroupWithSubgroups createGroup(UUID storeId, String actorId, UUID requestId, GroupData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		requireRequestId(requestId);
		var data = normalize(raw);
		try {
			if (!groups.createGroup(ownerId, requestId, data, actorId, Instant.now())
					&& !groups.matchesGroupRegistration(ownerId, requestId, data.name(), actorId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
			}
		} catch (DuplicateKeyException exception) {
			throw duplicateName("grupo");
		}
		return withSubgroups(ownerId, findGroup(ownerId, requestId));
	}

	@Transactional
	public GroupWithSubgroups updateGroup(UUID storeId, String actorId, UUID id, long version, GroupData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		findGroup(ownerId, id);
		try {
			var saved = groups.updateGroup(ownerId, id, normalize(raw), version, actorId, Instant.now())
					.orElseThrow(ProductGroupService::stale);
			return withSubgroups(ownerId, saved);
		} catch (DuplicateKeyException exception) {
			throw duplicateName("grupo");
		}
	}

	@Transactional
	public ProductSubgroup createSubgroup(UUID storeId, String actorId, UUID groupId, UUID requestId, SubgroupData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		requireRequestId(requestId);
		findGroup(ownerId, groupId);
		var data = normalize(raw);
		if (!groups.createSubgroup(ownerId, groupId, requestId, data, actorId, Instant.now())
				&& !groups.matchesSubgroupRegistration(ownerId, groupId, requestId, data.name(), actorId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
		}
		return findSubgroup(ownerId, groupId, requestId);
	}

	@Transactional
	public ProductSubgroup updateSubgroup(UUID storeId, String actorId, UUID groupId, UUID id, long version, SubgroupData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		findSubgroup(ownerId, groupId, id);
		return groups.updateSubgroup(ownerId, groupId, id, normalize(raw), version, actorId, Instant.now())
				.orElseThrow(ProductGroupService::stale);
	}

	private GroupWithSubgroups withSubgroups(UUID ownerId, ProductGroup group) {
		return new GroupWithSubgroups(group, groups.listSubgroups(ownerId).stream()
				.filter(subgroup -> subgroup.groupId().equals(group.id())).toList());
	}

	private ProductGroup findGroup(UUID ownerId, UUID id) {
		return groups.findGroup(ownerId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Grupo nao encontrado."));
	}

	private ProductSubgroup findSubgroup(UUID ownerId, UUID groupId, UUID id) {
		return groups.findSubgroup(ownerId, groupId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Subgrupo nao encontrado neste grupo."));
	}

	private static void requireRequestId(UUID requestId) {
		if (requestId == null) {
			throw Fields.bad("Informe requestId UUID.");
		}
	}

	private static ResponseStatusException stale() {
		return new ResponseStatusException(HttpStatus.CONFLICT,
				"Cadastro alterado em outra operacao. Recarregue e confira os dados antes de salvar novamente.");
	}

	private static ResponseStatusException duplicateName(String what) {
		return new ResponseStatusException(HttpStatus.CONFLICT, "Ja existe um " + what + " com este nome.");
	}

	static GroupData normalize(GroupData raw) {
		return new GroupData(Fields.required(raw.name(), 2, 60, "Nome do grupo"), Fields.limited(raw.ncm(), 20, "NCM"),
				Fields.percent(raw.commission(), 100, "Comissao"),
				Fields.percent(raw.assemblyCommission(), 100, "Comissao de montagem"),
				Fields.percent(raw.assemblyDiscount(), 100, "Desconto de montagem"),
				percentUpTo(raw.markup(), 1000, "Markup"), Fields.percent(raw.pautaDiscount(), 100, "Desconto de pauta"),
				Fields.percent(raw.flexibleDiscount(), 100, "Desconto flexivel"));
	}

	static SubgroupData normalize(SubgroupData raw) {
		return new SubgroupData(Fields.required(raw.name(), 2, 60, "Nome do subgrupo"),
				percentUpTo(raw.markup(), 1000, "Markup"), percentUpTo(raw.margin(), 1000, "Margem"),
				Fields.percent(raw.pautaDiscount(), 100, "Desconto de pauta"),
				Fields.percent(raw.flexibleDiscount(), 100, "Desconto flexivel"));
	}

	private static BigDecimal percentUpTo(BigDecimal value, int max, String label) {
		return Fields.percent(value, max, label);
	}
}
