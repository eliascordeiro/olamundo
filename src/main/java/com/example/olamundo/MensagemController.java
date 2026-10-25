package com.example.olamundo;

import java.util.List;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MensagemController {

	private final MensagemRepository mensagemRepository;

	public MensagemController(MensagemRepository mensagemRepository) {
		this.mensagemRepository = mensagemRepository;
	}

	@GetMapping("/api/mensagens")
	public List<Mensagem> listarMensagens() {
		return mensagemRepository.findAll();
	}

	@PostMapping("/api/mensagens")
	@SecurityRequirement(name = "csrf")
	@ResponseStatus(HttpStatus.CREATED)
	public Mensagem criarMensagem(@Valid @RequestBody NovaMensagemRequest request) {
		return mensagemRepository.save(new Mensagem(request.texto()));
	}

	public record NovaMensagemRequest(@NotBlank @Size(max = 2000) String texto) {
	}

}
