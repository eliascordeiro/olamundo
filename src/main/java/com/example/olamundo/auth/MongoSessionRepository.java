package com.example.olamundo.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Repository;

@Repository
public class MongoSessionRepository implements SessionRepository<MapSession> {
	private final MongoTemplate mongo;

	public MongoSessionRepository(MongoTemplate mongo) {
		this.mongo = mongo;
	}

	@Override
	public MapSession createSession() {
		var session = new MapSession();
		session.setMaxInactiveInterval(Duration.ofMinutes(30));
		return session;
	}

	@Override
	public void save(MapSession session) {
		List<StoredAttribute> attributes = new ArrayList<>();
		for (String name : session.getAttributeNames()) {
			Object value = session.getAttribute(name);
			if (value instanceof SecurityContext context && context.getAuthentication() != null) {
				attributes.add(new StoredAttribute(name, "principal", context.getAuthentication().getName()));
			} else if (value instanceof CsrfToken token) {
				attributes.add(new StoredAttribute(name, "csrf", token.getToken()));
			} else {
				throw new IllegalStateException("Unsupported session attribute: " + name);
			}
		}
		mongo.save(new StoredSession(session.getId(), session.getCreationTime(), session.getLastAccessedTime(),
				session.getMaxInactiveInterval().getSeconds(),
				session.getLastAccessedTime().plus(session.getMaxInactiveInterval()), attributes));
		if (!session.getOriginalId().equals(session.getId())) {
			deleteById(session.getOriginalId());
		}
	}

	@Override
	public MapSession findById(String id) {
		StoredSession stored = mongo.findById(id, StoredSession.class);
		if (stored == null) {
			return null;
		}
		// MongoDB's TTL monitor is asynchronous; enforce expiration on every read as well.
		if (!stored.expiresAt().isAfter(Instant.now())) {
			deleteById(id);
			return null;
		}
		var session = new MapSession(stored.id());
		session.setCreationTime(stored.createdAt());
		session.setLastAccessedTime(stored.lastAccessedAt());
		session.setMaxInactiveInterval(Duration.ofSeconds(stored.maxInactiveSeconds()));
		stored.attributes().forEach(attribute -> {
			switch (attribute.type()) {
				case "principal" -> {
					var context = SecurityContextHolder.createEmptyContext();
					context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(attribute.value(),
							null, AuthorityUtils.createAuthorityList("ROLE_USER")));
					session.setAttribute(attribute.name(), context);
				}
				case "csrf" -> session.setAttribute(attribute.name(),
						new DefaultCsrfToken("X-CSRF-TOKEN", "_csrf", attribute.value()));
				default -> throw new IllegalStateException("Unsupported persisted session attribute type");
			}
		});
		return session;
	}

	@Override
	public void deleteById(String id) {
		mongo.remove(org.springframework.data.mongodb.core.query.Query.query(
				org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)), StoredSession.class);
	}

	@Document("sessions")
	public record StoredSession(@Id String id, Instant createdAt, Instant lastAccessedAt,
			long maxInactiveSeconds, Instant expiresAt, List<StoredAttribute> attributes) {
	}

	public record StoredAttribute(String name, String type, String value) {
	}
}
