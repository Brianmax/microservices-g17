package com.virtualbank.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
class IdentityController {
  private final IdentityService service;

  IdentityController(IdentityService service) {
    this.service = service;
  }

  @PostMapping("/register")
  ResponseEntity<CustomerResponse> register(@Valid @RequestBody RegisterRequest r) {
    var value = service.register(r);
    return ResponseEntity.created(URI.create("/api/v1/customers/" + value.id())).body(value);
  }

  @PostMapping("/login")
  TokenResponse login(@Valid @RequestBody LoginRequest r) {
    return service.login(r);
  }

  @PostMapping("/refresh")
  TokenResponse refresh(@Valid @RequestBody TokenRequest r) {
    return service.refresh(r.refreshToken());
  }

  @PostMapping("/logout")
  ResponseEntity<Void> logout(@Valid @RequestBody TokenRequest r) {
    service.logout(r.refreshToken());
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/me")
  MeResponse me(Authentication a) {
    return service.me(UUID.fromString(a.getName()));
  }

  @PutMapping("/password")
  ResponseEntity<Void> password(Authentication a, @Valid @RequestBody PasswordRequest r) {
    service.changePassword(UUID.fromString(a.getName()), r);
    return ResponseEntity.noContent().build();
  }

  record RegisterRequest(
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @NotBlank @Email @Size(max = 320) String email,
      @NotBlank @Size(min = 12, max = 128) String password) {}

  record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {}

  record TokenRequest(@NotBlank String refreshToken) {}

  record PasswordRequest(
      @NotBlank String currentPassword, @NotBlank @Size(min = 12, max = 128) String newPassword) {}

  record TokenResponse(String accessToken, String refreshToken, String tokenType, long expiresIn) {}

  record CustomerResponse(
      UUID id,
      String firstName,
      String lastName,
      String email,
      String status,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}

  record MeResponse(
      UUID id,
      String email,
      String status,
      java.util.Set<String> roles,
      java.util.Set<String> permissions) {}
}
