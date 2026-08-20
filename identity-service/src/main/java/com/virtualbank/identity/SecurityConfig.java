package com.virtualbank.identity;

import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.*;
import com.nimbusds.jose.proc.SecurityContext;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
class SecurityConfig {
  @Bean
  SecurityFilterChain chain(HttpSecurity h) throws Exception {
    return h.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health",
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            o ->
                o.jwt(
                    j ->
                        j.jwtAuthenticationConverter(
                            jwt ->
                                new JwtAuthenticationToken(
                                    jwt,
                                    jwt.getClaimAsStringList("permissions").stream()
                                        .map(SimpleGrantedAuthority::new)
                                        .toList(),
                                    jwt.getSubject()))))
        .build();
  }

  @Bean
  PasswordEncoder passwords(@Value("${security.password.bcrypt-strength}") int strength) {
    return new BCryptPasswordEncoder(strength);
  }

  @Bean
  JwtEncoder encoder(
      @Value("${security.jwt.public-key-location}") Resource pub,
      @Value("${security.jwt.private-key-location}") Resource priv)
      throws Exception {
    RSAPublicKey p = publicKey(pub);
    String pem =
        priv.getContentAsString(StandardCharsets.US_ASCII)
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s", "");
    RSAPrivateKey k =
        (RSAPrivateKey)
            KeyFactory.getInstance("RSA")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)));
    JWKSource<SecurityContext> source =
        new ImmutableJWKSet<>(
            new JWKSet(new com.nimbusds.jose.jwk.RSAKey.Builder(p).privateKey(k).build()));
    return new NimbusJwtEncoder(source);
  }

  @Bean
  JwtDecoder decoder(
      @Value("${security.jwt.public-key-location}") Resource pub,
      @Value("${security.jwt.issuer}") String issuer,
      @Value("${security.jwt.audience}") String audience,
      JdbcClient db)
      throws Exception {
    NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(publicKey(pub)).build();
    OAuth2TokenValidator<Jwt> audienceValidator =
        jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required audience is missing", null));
    OAuth2TokenValidator<Jwt> versionValidator =
        jwt -> {
          try {
            Long current =
                db.sql("SELECT auth_version FROM credentials WHERE user_id=? AND status='ACTIVE'")
                    .param(UUID.fromString(jwt.getSubject()))
                    .query(Long.class)
                    .optional()
                    .orElse(null);
            Number claimed = jwt.getClaim("ver");
            return current != null && claimed != null && current.longValue() == claimed.longValue()
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Authentication version is stale", null));
          } catch (RuntimeException e) {
            return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Invalid token subject", null));
          }
        };
    d.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audienceValidator, versionValidator));
    return d;
  }

  private RSAPublicKey publicKey(Resource r) throws Exception {
    String pem =
        r.getContentAsString(StandardCharsets.US_ASCII)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    return (RSAPublicKey)
        KeyFactory.getInstance("RSA")
            .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
  }
}
