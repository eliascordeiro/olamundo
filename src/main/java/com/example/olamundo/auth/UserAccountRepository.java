package com.example.olamundo.auth;

import java.util.Optional;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface UserAccountRepository extends MongoRepository<UserAccount, String> {
	Optional<UserAccount> findByEmail(String email);
}
