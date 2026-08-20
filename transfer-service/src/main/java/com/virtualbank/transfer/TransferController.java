package com.virtualbank.transfer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/transfers")
class TransferController {
  private final TransferOrchestrator service;

  TransferController(TransferOrchestrator service) {
    this.service = service;
  }

  @PostMapping
  ResponseEntity<Response> create(Authentication auth, @Valid @RequestBody Request request) {
    authority(auth, "transfer:create:self", "transfer:create:any");
    var result =
        service.execute(UUID.fromString(auth.getName()), has(auth, "transfer:create:any"), request);
    return result.created()
        ? ResponseEntity.created(URI.create("/api/v1/transfers/" + result.response().id()))
            .body(result.response())
        : ResponseEntity.ok(result.response());
  }

  @GetMapping("/{id}")
  Response get(Authentication auth, @PathVariable UUID id) {
    var value = service.find(id);
    if (!has(auth, "transfer:read:any")
        && (!has(auth, "transfer:read:self")
            || !value.requesterId().toString().equals(auth.getName())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    return value;
  }

  @GetMapping
  List<Response> list(Authentication auth) {
    authority(auth, "transfer:read:self", "transfer:read:any");
    return has(auth, "transfer:read:any")
        ? service.all()
        : service.byRequester(UUID.fromString(auth.getName()));
  }

  private void authority(Authentication a, String... values) {
    if (Arrays.stream(values).noneMatch(v -> has(a, v)))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private boolean has(Authentication a, String v) {
    return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals(v));
  }

  record Request(
      @NotNull UUID sourceAccountId,
      @NotNull UUID destinationAccountId,
      @NotNull @DecimalMin("0.0001") @Digits(integer = 15, fraction = 4) BigDecimal amount,
      @Size(max = 255) String description,
      @NotBlank @Size(max = 100) String idempotencyKey) {}

  record Response(
      UUID id,
      UUID requesterId,
      UUID sourceAccountId,
      UUID destinationAccountId,
      BigDecimal amount,
      BigDecimal destinationAmount,
      String sourceCurrency,
      String destinationCurrency,
      BigDecimal exchangeRate,
      LocalDate exchangeRateDate,
      String exchangeRateProvider,
      String status,
      String reference,
      String idempotencyKey,
      String description,
      Instant createdAt,
      Instant completedAt,
      String failureReason) {}
}
