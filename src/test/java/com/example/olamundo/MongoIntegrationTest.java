package com.example.olamundo;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mongodb.MongoDBContainer;

public abstract class MongoIntegrationTest {
	private static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");

	static {
		MONGO.start();
	}

	@DynamicPropertySource
	static void mongoProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.mongodb.uri", MONGO::getReplicaSetUrl);
		registry.add("app.session.secure-cookie", () -> "false");
	}
}
