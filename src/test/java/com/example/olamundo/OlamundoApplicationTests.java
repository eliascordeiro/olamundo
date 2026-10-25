package com.example.olamundo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest
class OlamundoApplicationTests extends MongoIntegrationTest {
	@Autowired ApplicationContext context;

	@Test
	void contextLoads() {
		assertThat(context.containsBean("platformApi")).isFalse();
		assertThat(context.getBeanNamesForType(org.springdoc.webmvc.api.OpenApiWebMvcResource.class)).isEmpty();
	}

}
