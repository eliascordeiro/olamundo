package com.example.olamundo.customers.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.example.olamundo.customers.domain.DeliveryAddress;
import com.example.olamundo.customers.domain.DeliveryAddressData;
import com.example.olamundo.stores.application.StoreAuthorization;
import com.example.olamundo.stores.application.StoreRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Enderecos de entrega do cliente: qualquer membro da loja da empresa consulta, cadastra, edita e remove. */
@Service
public class DeliveryAddressService {
	private final DeliveryAddressRepository addresses;
	private final CustomerRepository customers;
	private final StoreAuthorization authorization;
	private final StoreRepository stores;

	public DeliveryAddressService(DeliveryAddressRepository addresses, CustomerRepository customers,
			StoreAuthorization authorization, StoreRepository stores) {
		this.addresses = addresses;
		this.customers = customers;
		this.authorization = authorization;
		this.stores = stores;
	}

	@Transactional(readOnly = true)
	public List<DeliveryAddress> list(UUID storeId, String actorId, UUID customerId) {
		UUID ownerId = scope(storeId, actorId, customerId);
		return addresses.list(ownerId, customerId);
	}

	@Transactional
	public DeliveryAddress create(UUID storeId, String actorId, UUID customerId, UUID requestId, DeliveryAddressData raw) {
		UUID ownerId = scope(storeId, actorId, customerId);
		if (requestId == null) {
			throw bad("Informe requestId UUID.");
		}
		var data = normalize(raw);
		if (!addresses.create(ownerId, customerId, requestId, data, actorId, Instant.now())
				&& !addresses.matchesRegistration(ownerId, customerId, requestId, data.street(), actorId)) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador ja usado com outro endereco.");
		}
		return find(ownerId, customerId, requestId);
	}

	@Transactional
	public DeliveryAddress update(UUID storeId, String actorId, UUID customerId, UUID id, long version, DeliveryAddressData raw) {
		UUID ownerId = scope(storeId, actorId, customerId);
		find(ownerId, customerId, id);
		return addresses.update(ownerId, customerId, id, normalize(raw), version, actorId, Instant.now()).orElseThrow(
				AddressConflict::new);
	}

	@Transactional
	public void delete(UUID storeId, String actorId, UUID customerId, UUID id, long version) {
		UUID ownerId = scope(storeId, actorId, customerId);
		find(ownerId, customerId, id);
		if (!addresses.delete(ownerId, customerId, id, version)) {
			throw new AddressConflict();
		}
	}

	private UUID scope(UUID storeId, String actorId, UUID customerId) {
		authorization.requireMember(storeId, actorId);
		UUID ownerId = stores.findOwnerId(storeId).orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
				"Esta loja ainda nao pertence a uma empresa. Fale com o administrador da plataforma."));
		customers.find(ownerId, customerId).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado."));
		return ownerId;
	}

	private DeliveryAddress find(UUID ownerId, UUID customerId, UUID id) {
		return addresses.find(ownerId, customerId, id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Endereco de entrega nao encontrado."));
	}

	static DeliveryAddressData normalize(DeliveryAddressData raw) {
		String street = squash(raw.street());
		if (street == null || street.length() < 2 || street.length() > 100) {
			throw bad("Endereco deve ter de 2 a 100 caracteres.");
		}
		String zip = squash(raw.zip()) == null ? null : raw.zip().replaceAll("\\D", "");
		if (zip != null && zip.length() != 8) {
			throw bad("CEP deve ter 8 digitos.");
		}
		String state = squash(raw.state()) == null ? null : raw.state().strip().toUpperCase();
		if (state != null && !state.matches("[A-Z]{2}")) {
			throw bad("UF deve ter 2 letras.");
		}
		return new DeliveryAddressData(zip, street, limited(raw.number(), 20, "Numero"),
				limited(raw.complement(), 100, "Complemento"), limited(raw.neighborhood(), 60, "Bairro"),
				limited(raw.city(), 60, "Cidade"), state);
	}

	private static String limited(String value, int max, String label) {
		String text = squash(value);
		if (text != null && text.length() > max) {
			throw bad(label + " deve ter no maximo " + max + " caracteres.");
		}
		return text;
	}

	private static String squash(String value) {
		return value == null || value.isBlank() ? null : value.strip().replaceAll("\\s+", " ");
	}

	private static ResponseStatusException bad(String message) {
		return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
	}

	private static final class AddressConflict extends ResponseStatusException {
		AddressConflict() {
			super(HttpStatus.CONFLICT,
					"Endereco alterado em outra operacao. Recarregue e confira os dados antes de salvar novamente.");
		}
	}
}
