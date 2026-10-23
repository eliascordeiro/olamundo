package com.example.olamundo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "mensagens")
public class Mensagem {

	@Id
	private String id;

	private String texto;

	private Instant criadaEm;

	public Mensagem() {
	}

	public Mensagem(String texto) {
		this.texto = texto;
		this.criadaEm = Instant.now();
	}

	public String getId() {
		return id;
	}

	public void setId(String id) {
		this.id = id;
	}

	public String getTexto() {
		return texto;
	}

	public void setTexto(String texto) {
		this.texto = texto;
	}

	public Instant getCriadaEm() {
		return criadaEm;
	}

	public void setCriadaEm(Instant criadaEm) {
		this.criadaEm = criadaEm;
	}

}
