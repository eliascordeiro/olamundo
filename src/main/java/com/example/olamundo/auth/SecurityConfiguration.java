package com.example.olamundo.auth;

import java.util.List;

import jakarta.servlet.DispatcherType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfiguration {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder(12);
	}

	@Bean
	AuthenticationManager authenticationManager(UserAccountRepository repository, PasswordEncoder encoder) {
		var provider = new DaoAuthenticationProvider(email -> repository.findByEmail(email)
				.map(account -> User.withUsername(account.email()).password(account.passwordHash())
						.roles("USER").build())
				.orElseThrow(() -> new UsernameNotFoundException("Invalid credentials")));
		provider.setPasswordEncoder(encoder);
		return new ProviderManager(provider);
	}

	@Bean
	HttpSessionSecurityContextRepository securityContextRepository() {
		return new HttpSessionSecurityContextRepository();
	}

	@Bean
	@Order(1)
	SecurityFilterChain documentationFilterChain(HttpSecurity http,
			HttpSessionSecurityContextRepository contextRepository,
			@Value("${app.api-docs.enabled:false}") boolean enabled) throws Exception {
		return http
				.securityMatcher("/api/docs", "/api/docs/**", "/api/openapi", "/api/openapi/**",
						"/api/openapi.yaml", "/api/swagger-ui/**", "/swagger-ui/**")
				.securityContext(context -> context.securityContextRepository(contextRepository))
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(auth -> {
					if (enabled) auth.anyRequest().authenticated();
					else auth.anyRequest().denyAll();
				})
				.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) ->
						ApiExceptionHandler.writeError(response, 401, "Entre na sua conta para continuar.")))
				.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
						"default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
						+ "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'")))
				.build();
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http,
			HttpSessionSecurityContextRepository contextRepository) throws Exception {
		return http
				.cors(cors -> {})
				.csrf(csrf -> csrf
						.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
						.csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
				.securityContext(context -> context.securityContextRepository(contextRepository))
				.requestCache(cache -> cache.disable())
				.authorizeHttpRequests(auth -> auth
						.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
						.requestMatchers(HttpMethod.GET, "/", "/api/ola", "/api/auth/csrf", "/api/health").permitAll()
						.requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/register").permitAll()
						.anyRequest().authenticated())
				.exceptionHandling(errors -> errors
						.authenticationEntryPoint((request, response, exception) ->
								ApiExceptionHandler.writeError(response, 401, "Entre na sua conta para continuar."))
						.accessDeniedHandler((request, response, exception) ->
								ApiExceptionHandler.writeError(response, 403,
										"Sessao de seguranca expirada. Atualize a pagina e tente novamente.")))
				.logout(logout -> logout.logoutUrl("/api/auth/logout")
						.logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
				.headers(headers -> headers
						.contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
				.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(
			@Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> origins) {
		var config = new CorsConfiguration();
		config.setAllowedOrigins(origins);
		config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
		config.setAllowedHeaders(List.of("Content-Type", "X-CSRF-TOKEN"));
		config.setAllowCredentials(true);
		var source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", config);
		return source;
	}

	@Bean
	DefaultCookieSerializer cookieSerializer(
			@Value("${app.session.secure-cookie:true}") boolean secureCookie) {
		var serializer = new DefaultCookieSerializer();
		serializer.setCookieName("BUSINESS_SESSION");
		serializer.setUseHttpOnlyCookie(true);
		serializer.setUseSecureCookie(secureCookie);
		serializer.setSameSite("Lax");
		serializer.setCookiePath("/");
		return serializer;
	}
}
