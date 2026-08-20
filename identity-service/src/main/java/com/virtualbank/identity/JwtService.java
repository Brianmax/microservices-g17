package com.virtualbank.identity;

import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
class JwtService {
  private final JwtEncoder encoder;
  private final String issuer, audience;
  private final Duration ttl;

  JwtService(
      JwtEncoder encoder,
      @Value("${security.jwt.issuer}") String issuer,
      @Value("${security.jwt.audience}") String audience,
      @Value("${security.jwt.access-token-ttl}") Duration ttl) {
    this.encoder = encoder;
    this.issuer = issuer;
    this.audience = audience;
    this.ttl = ttl;
  }

  String create(UUID id, long version, Set<String> roles, Set<String> permissions) {
    Instant now = Instant.now();
    var claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of(audience))
            .subject(id.toString())
            .id(UUID.randomUUID().toString())
            .issuedAt(now)
            .notBefore(now)
            .expiresAt(now.plus(ttl))
            .claim("roles", roles)
            .claim("permissions", permissions)
            .claim("ver", version)
            .build();
    return encoder
        .encode(
            JwtEncoderParameters.from(
                JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims))
        .getTokenValue();
  }

  Duration ttl() {
    return ttl;
  }
}
