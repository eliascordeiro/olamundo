package com.example.olamundo.suppliers.application;

import java.time.Instant;
import java.util.UUID;

import com.example.olamundo.customers.domain.TaxId;
import com.example.olamundo.shared.Fields;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.CompanyAccess;
import com.example.olamundo.suppliers.domain.Supplier;
import com.example.olamundo.suppliers.domain.SupplierData;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Fornecedores pertencem a empresa e valem para todas as lojas dela.
 * Qualquer membro da loja consulta; somente OWNER e MANAGER cadastram e editam.
 */
@Service
public class SupplierService {
	private static final String WRITER_MESSAGE = "Somente OWNER ou MANAGER pode cadastrar ou editar fornecedores.";

	private final SupplierRepository suppliers;
	private final CompanyAccess company;

	public SupplierService(SupplierRepository suppliers, CompanyAccess company) {
		this.suppliers = suppliers;
		this.company = company;
	}

	@Transactional(readOnly = true)
	public PageSlice<Supplier> list(UUID storeId, String actorId, String query, int page, int size) {
		UUID ownerId = company.companyOf(storeId, actorId);
		PageSlice.validate(page, size);
		return PageSlice.from(suppliers.list(ownerId, query, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public Supplier get(UUID storeId, String actorId, UUID id) {
		return find(company.companyOf(storeId, actorId), id);
	}

	@Transactional
	public Supplier create(UUID storeId, String actorId, UUID requestId, SupplierData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		if (requestId == null) {
			throw Fields.bad("Informe requestId UUID.");
		}
		var data = normalize(raw);
		try {
			if (!suppliers.create(ownerId, requestId, data, actorId, Instant.now())
					&& !suppliers.matchesRegistration(ownerId, requestId, data.name(), actorId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
			}
		} catch (DuplicateKeyException exception) {
			throw duplicateDocument();
		}
		return find(ownerId, requestId);
	}

	@Transactional
	public Supplier update(UUID storeId, String actorId, UUID id, long version, SupplierData raw) {
		UUID ownerId = company.companyForWriter(storeId, actorId, WRITER_MESSAGE);
		find(ownerId, id);
		try {
			return suppliers.update(ownerId, id, normalize(raw), version, actorId, Instant.now()).orElseThrow(() ->
					new ResponseStatusException(HttpStatus.CONFLICT,
							"Fornecedor alterado em outra operacao. Recarregue e confira os dados antes de salvar novamente."));
		} catch (DuplicateKeyException exception) {
			throw duplicateDocument();
		}
	}

	private Supplier find(UUID ownerId, UUID id) {
		return suppliers.find(ownerId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Fornecedor nao encontrado."));
	}

	private static ResponseStatusException duplicateDocument() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "Ja existe um fornecedor com este CNPJ/CPF.");
	}

	static SupplierData normalize(SupplierData raw) {
		String document = Fields.blank(raw.document()) == null ? null : raw.document().replaceAll("\\D", "");
		if (document != null && !TaxId.isValid(document)) {
			throw Fields.bad("CNPJ/CPF invalido.");
		}
		return new SupplierData(Fields.required(raw.name(), 2, 120, "Nome do fornecedor"), document,
				Fields.limited(raw.stateRegistration(), 20, "Inscricao estadual"), Fields.email(raw.email()),
				Fields.phone(raw.phone()), Fields.phone(raw.fax()), Fields.limited(raw.contact(), 60, "Contato"),
				Fields.zip(raw.zip()), Fields.limited(raw.street(), 100, "Endereco"),
				Fields.limited(raw.neighborhood(), 60, "Bairro"), Fields.limited(raw.city(), 60, "Cidade"),
				Fields.state(raw.state()), raw.active());
	}
}
