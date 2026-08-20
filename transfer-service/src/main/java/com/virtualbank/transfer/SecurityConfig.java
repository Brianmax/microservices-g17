package com.virtualbank.transfer;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.io.Resource;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
class SecurityConfig {
  @Bean
  SecurityFilterChain chain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a -> a.requestMatchers("/actuator/health").permitAll().anyRequest().authenticated())
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
  JwtDecoder decoder(
      @Value("${security.jwt.public-key-location}") Resource key,
      @Value("${security.jwt.issuer}") String issuer,
      @Value("${security.jwt.audience}") String audience)
      throws Exception {
    String pem =
        key.getContentAsString(StandardCharsets.US_ASCII)
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replaceAll("\\s", "");
    RSAPublicKey rsa =
        (RSAPublicKey)
            KeyFactory.getInstance("RSA")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsa).build();
    OAuth2TokenValidator<Jwt> aud =
        jwt ->
            jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Required audience is missing", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), aud));
    return decoder;
  }
}
