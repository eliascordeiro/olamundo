package com.example.olamundo;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OlaMundoController {

	@GetMapping("/")
	public String olaMundo() {
		return "Olá Mundo!";
	}

	@GetMapping("/api/ola")
	public MensagemResponse olaMundoJson() {
		return new MensagemResponse("Olá Mundo!");
	}

	public record MensagemResponse(String mensagem) {
	}

}
