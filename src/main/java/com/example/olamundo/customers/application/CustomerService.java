package com.example.olamundo.customers.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Pattern;

import com.example.olamundo.customers.domain.Customer;
import com.example.olamundo.customers.domain.CustomerData;
import com.example.olamundo.customers.domain.TaxId;
import com.example.olamundo.shared.PageSlice;
import com.example.olamundo.stores.application.StoreAuthorization;
import com.example.olamundo.stores.application.StoreRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Clientes pertencem a empresa (proprietario) da loja e valem para todas as lojas dela.
 * Qualquer membro da loja consulta, cadastra e edita; nao membros recebem 404.
 */
@Service
public class CustomerService {
	private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
	private static final Pattern PHONE = Pattern.compile("^[0-9 ()+./-]{3,20}$");

	private final CustomerRepository customers;
	private final StoreAuthorization authorization;
	private final StoreRepository stores;

	public CustomerService(CustomerRepository customers, StoreAuthorization authorization, StoreRepository stores) {
		this.customers = customers;
		this.authorization = authorization;
		this.stores = stores;
	}

	@Transactional(readOnly = true)
	public PageSlice<Customer> list(UUID storeId, String actorId, String query, int page, int size) {
		UUID ownerId = ownerOf(storeId, actorId);
		PageSlice.validate(page, size);
		return PageSlice.from(customers.list(ownerId, query, size + 1, (long) page * size), page, size);
	}

	@Transactional(readOnly = true)
	public Customer get(UUID storeId, String actorId, UUID id) {
		return find(ownerOf(storeId, actorId), id);
	}

	@Transactional
	public Customer create(UUID storeId, String actorId, UUID requestId, CustomerData raw) {
		UUID ownerId = ownerOf(storeId, actorId);
		if (requestId == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe requestId UUID.");
		}
		var data = normalize(raw);
		try {
			if (!customers.create(ownerId, requestId, data, actorId, Instant.now())
					&& !customers.matchesRegistration(ownerId, requestId, data.name(), actorId)) {
				throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro cadastro.");
			}
		} catch (DuplicateKeyException exception) {
			throw duplicateDocument();
		}
		return find(ownerId, requestId);
	}

	@Transactional
	public Customer update(UUID storeId, String actorId, UUID id, long version, CustomerData raw) {
		UUID ownerId = ownerOf(storeId, actorId);
		find(ownerId, id);
		try {
			return customers.update(ownerId, id, normalize(raw), version, actorId, Instant.now()).orElseThrow(() ->
					new ResponseStatusException(HttpStatus.CONFLICT,
							"Cliente alterado em outra operacao. Recarregue e confira os dados antes de salvar novamente."));
		} catch (DuplicateKeyException exception) {
			throw duplicateDocument();
		}
	}

	private UUID ownerOf(UUID storeId, String actorId) {
		authorization.requireMember(storeId, actorId);
		return stores.findOwnerId(storeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
				"Esta loja ainda nao pertence a uma empresa. Fale com o administrador da plataforma."));
	}

	private Customer find(UUID ownerId, UUID id) {
		return customers.find(ownerId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado."));
	}

	private static ResponseStatusException duplicateDocument() {
		return new ResponseStatusException(HttpStatus.CONFLICT, "Ja existe um cliente com este CPF/CNPJ.");
	}

	static CustomerData normalize(CustomerData raw) {
		String name = squash(raw.name());
		if (name == null || name.length() < 2 || name.length() > 120) {
			throw bad("Nome do cliente deve ter de 2 a 120 caracteres.");
		}
		String document = blank(raw.document()) == null ? null : raw.document().replaceAll("\\D", "");
		if (document != null && !TaxId.isValid(document)) {
			throw bad("CPF/CNPJ invalido.");
		}
		LocalDate birth = raw.birthDate();
		if (birth != null && (birth.isBefore(LocalDate.of(1900, 1, 1)) || birth.isAfter(LocalDate.now()))) {
			throw bad("Data de nascimento invalida.");
		}
		String email = blank(raw.email()) == null ? null : raw.email().strip().toLowerCase();
		if (email != null && (email.length() > 254 || !EMAIL.matcher(email).matches())) {
			throw bad("E-mail invalido.");
		}
		String zip = blank(raw.zip()) == null ? null : raw.zip().replaceAll("\\D", "");
		if (zip != null && zip.length() != 8) {
			throw bad("CEP deve ter 8 digitos.");
		}
		String state = blank(raw.state()) == null ? null : raw.state().strip().toUpperCase();
		if (state != null && !state.matches("[A-Z]{2}")) {
			throw bad("UF deve ter 2 letras.");
		}
		return new CustomerData(name, document, limited(raw.rg(), 20, "RG"), limited(raw.stateRegistration(), 20, "Inscricao estadual"),
				birth, phone(raw.phone()), phone(raw.mobile()), email, zip, limited(raw.street(), 100, "Endereco"),
				limited(raw.number(), 20, "Numero"), limited(raw.complement(), 100, "Complemento"),
				limited(raw.neighborhood(), 60, "Bairro"), limited(raw.city(), 60, "Cidade"), state,
				limited(raw.ibge(), 10, "IBGE"));
	}

	private static String phone(String value) {
		String text = squash(value);
		if (text != null && !PHONE.matcher(text).matches()) {
			throw bad("Telefone invalido. Use ate 20 caracteres entre digitos, espacos e ( ) + - . /");
		}
		return text;
	}

	private static String limited(String value, int max, String label) {
		String text = squash(value);
		if (text != null && text.length() > max) {
			throw bad(label + " deve ter no maximo " + max + " caracteres.");
		}
		return text;
	}

	private static String blank(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static String squash(String value) {
		return blank(value) == null ? null : value.strip().replaceAll("\\s+", " ");
	}

	private static ResponseStatusException bad(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}
}
