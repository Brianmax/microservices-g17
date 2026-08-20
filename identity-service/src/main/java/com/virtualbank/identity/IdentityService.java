package com.virtualbank.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

@Service
class IdentityService {
  private final JdbcClient db;
  private final PasswordEncoder passwords;
  private final JwtService jwt;
  private final RestClient customers;
  private final Duration refreshTtl;

  IdentityService(
      JdbcClient db,
      PasswordEncoder passwords,
      JwtService jwt,
      RestClient.Builder rest,
      @Value("${services.customer.base-url}") String customerUrl,
      @Value("${security.jwt.refresh-token-ttl}") Duration refreshTtl) {
    this.db = db;
    this.passwords = passwords;
    this.jwt = jwt;
    this.customers = rest.baseUrl(customerUrl).build();
    this.refreshTtl = refreshTtl;
  }

  @Transactional
  IdentityController.CustomerResponse register(IdentityController.RegisterRequest r) {
    validatePassword(r.password());
    UUID id = UUID.randomUUID();
    String email = r.email().trim().toLowerCase(Locale.ROOT);
    IdentityController.CustomerResponse customer = null;
    try {
      customer =
          customers
              .post()
              .uri("/api/v1/customers/internal")
              .body(
                  Map.of(
                      "id",
                      id,
                      "firstName",
                      r.firstName(),
                      "lastName",
                      r.lastName(),
                      "email",
                      email))
              .retrieve()
              .body(IdentityController.CustomerResponse.class);
      Instant now = Instant.now();
      db.sql(
              "INSERT INTO credentials(user_id,email,password_hash,status,auth_version,password_changed_at,created_at,updated_at) VALUES(?,?,?,?,1,?,?,?)")
          .params(
              id,
              email,
              passwords.encode(r.password()),
              "ACTIVE",
              Timestamp.from(now),
              Timestamp.from(now),
              Timestamp.from(now))
          .update();
      db.sql(
              "INSERT INTO user_roles(user_id,role_id) VALUES(?,'10000000-0000-0000-0000-000000000001')")
          .param(id)
          .update();
      return customer;
    } catch (DuplicateKeyException e) {
      rollback(id, customer);
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate email");
    } catch (RuntimeException e) {
      rollback(id, customer);
      throw e;
    }
  }

  IdentityController.TokenResponse login(IdentityController.LoginRequest r) {
    Credential c = credentialByEmail(r.email());
    if (!"ACTIVE".equals(c.status()) || !passwords.matches(r.password(), c.hash())) throw invalid();
    return pair(c, UUID.randomUUID());
  }

  @Transactional(noRollbackFor = ResponseStatusException.class)
  IdentityController.TokenResponse refresh(String raw) {
    String hash = hash(raw);
    Token t =
        db.sql(
                "SELECT id,user_id,family_id,expires_at,revoked_at FROM refresh_tokens WHERE token_hash=? FOR UPDATE")
            .param(hash)
            .query(Token.class)
            .optional()
            .orElseThrow(this::invalid);
    if (t.revokedAt() != null) {
      db.sql("UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE family_id=?")
          .param(t.familyId())
          .update();
      throw invalid();
    }
    if (!t.expiresAt().isAfter(Instant.now())) throw invalid();
    Credential c = credential(t.userId());
    String next = random();
    UUID nextId = UUID.randomUUID();
    db.sql(
            "INSERT INTO refresh_tokens(id,user_id,token_hash,family_id,expires_at,created_at) VALUES(?,?,?,?,?,now())")
        .params(
            nextId,
            c.id(),
            hash(next),
            t.familyId(),
            Timestamp.from(Instant.now().plus(refreshTtl)))
        .update();
    db.sql("UPDATE refresh_tokens SET revoked_at=now(),replaced_by_id=? WHERE id=?")
        .params(nextId, t.id())
        .update();
    return tokenResponse(c, next);
  }

  @Transactional
  void logout(String raw) {
    db.sql(
            "UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE family_id=(SELECT family_id FROM refresh_tokens WHERE token_hash=?)")
        .param(hash(raw))
        .update();
  }

  IdentityController.MeResponse me(UUID id) {
    Credential c = credential(id);
    return new IdentityController.MeResponse(id, c.email(), c.status(), roles(id), permissions(id));
  }

  @Transactional
  void changePassword(UUID id, IdentityController.PasswordRequest r) {
    validatePassword(r.newPassword());
    Credential c = credential(id);
    if (!passwords.matches(r.currentPassword(), c.hash())) throw invalid();
    if (passwords.matches(r.newPassword(), c.hash()))
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY, "New password must be different");
    db.sql(
            "UPDATE credentials SET password_hash=?,auth_version=auth_version+1,password_changed_at=now(),updated_at=now() WHERE user_id=?")
        .params(passwords.encode(r.newPassword()), id)
        .update();
    db.sql("UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?")
        .param(id)
        .update();
  }

  List<SecurityAdminController.RoleView> allRoles() {
    return db.sql(
            "SELECT r.id,r.code,r.description,COALESCE(array_agg(p.code ORDER BY p.code) FILTER (WHERE p.code IS NOT NULL),ARRAY[]::varchar[]) permissions FROM roles r LEFT JOIN role_permissions rp ON rp.role_id=r.id LEFT JOIN permissions p ON p.id=rp.permission_id GROUP BY r.id,r.code,r.description ORDER BY r.code")
        .query(SecurityAdminController.RoleView.class)
        .list();
  }

  List<SecurityAdminController.PermissionView> allPermissions() {
    return db.sql("SELECT id,code,description FROM permissions ORDER BY code")
        .query(SecurityAdminController.PermissionView.class)
        .list();
  }

  @Transactional
  void replaceRoles(UUID userId, Set<String> roleCodes) {
    credential(userId);
    List<UUID> ids =
        db.sql("SELECT id FROM roles WHERE code IN (:codes)")
            .param("codes", roleCodes)
            .query(UUID.class)
            .list();
    if (ids.size() != roleCodes.size())
      throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Unknown role");
    db.sql("DELETE FROM user_roles WHERE user_id=?").param(userId).update();
    ids.forEach(
        roleId ->
            db.sql("INSERT INTO user_roles(user_id,role_id) VALUES(?,?)")
                .params(userId, roleId)
                .update());
    db.sql("UPDATE credentials SET auth_version=auth_version+1,updated_at=now() WHERE user_id=?")
        .param(userId)
        .update();
    db.sql("UPDATE refresh_tokens SET revoked_at=COALESCE(revoked_at,now()) WHERE user_id=?")
        .param(userId)
        .update();
  }

  private IdentityController.TokenResponse pair(Credential c, UUID family) {
    String raw = random();
    db.sql(
            "INSERT INTO refresh_tokens(id,user_id,token_hash,family_id,expires_at,created_at) VALUES(?,?,?,?,?,now())")
        .params(
            UUID.randomUUID(),
            c.id(),
            hash(raw),
            family,
            Timestamp.from(Instant.now().plus(refreshTtl)))
        .update();
    return tokenResponse(c, raw);
  }

  private IdentityController.TokenResponse tokenResponse(Credential c, String refresh) {
    return new IdentityController.TokenResponse(
        jwt.create(c.id(), c.version(), roles(c.id()), permissions(c.id())),
        refresh,
        "Bearer",
        jwt.ttl().toSeconds());
  }

  private Credential credential(UUID id) {
    return db.sql(
            "SELECT user_id id,email,password_hash hash,status,auth_version version FROM credentials WHERE user_id=?")
        .param(id)
        .query(Credential.class)
        .optional()
        .orElseThrow(this::invalid);
  }

  private Credential credentialByEmail(String email) {
    return db.sql(
            "SELECT user_id id,email,password_hash hash,status,auth_version version FROM credentials WHERE lower(email)=lower(?)")
        .param(email.trim())
        .query(Credential.class)
        .optional()
        .orElseThrow(this::invalid);
  }

  private Set<String> roles(UUID id) {
    return new LinkedHashSet<>(
        db.sql(
                "SELECT r.code FROM roles r JOIN user_roles ur ON ur.role_id=r.id WHERE ur.user_id=? ORDER BY r.code")
            .param(id)
            .query(String.class)
            .list());
  }

  private Set<String> permissions(UUID id) {
    return new LinkedHashSet<>(
        db.sql(
                "SELECT DISTINCT p.code FROM permissions p JOIN role_permissions rp ON rp.permission_id=p.id JOIN user_roles ur ON ur.role_id=rp.role_id WHERE ur.user_id=? ORDER BY p.code")
            .param(id)
            .query(String.class)
            .list());
  }

  private String random() {
    return UUID.randomUUID() + "." + UUID.randomUUID();
  }

  private String hash(String raw) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private ResponseStatusException invalid() {
    return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials or token");
  }

  private void validatePassword(String password) {
    if (password == null
        || password.length() < 12
        || password.length() > 128
        || password.chars().noneMatch(Character::isUpperCase)
        || password.chars().noneMatch(Character::isLowerCase)
        || password.chars().noneMatch(Character::isDigit))
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "Password must contain uppercase, lowercase and digit characters");
  }

  private void rollback(UUID id, Object c) {
    if (c != null)
      try {
        customers.delete().uri("/api/v1/customers/internal/{id}", id).retrieve().toBodilessEntity();
      } catch (Exception ignored) {
      }
  }

  record Credential(UUID id, String email, String hash, String status, long version) {}

  record Token(UUID id, UUID userId, UUID familyId, Instant expiresAt, Instant revokedAt) {}
}
