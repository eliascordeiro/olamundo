package com.example.olamundo.auth;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.domain.Sort.Direction;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

@Configuration
@EnableSpringHttpSession
public class MongoAuthConfiguration {

	@Bean
	ApplicationRunner userEmailIndex(MongoTemplate mongoTemplate) {
		return args -> mongoTemplate.indexOps(UserAccount.class)
				.createIndex(new Index().on("email", Direction.ASC).unique());
	}

	@Bean
	ApplicationRunner sessionExpiryIndex(MongoTemplate mongoTemplate) {
		return args -> mongoTemplate.indexOps(MongoSessionRepository.StoredSession.class)
				.createIndex(new Index().on("expiresAt", Direction.ASC).expire(java.time.Duration.ZERO));
	}
}
