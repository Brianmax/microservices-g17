package com.virtualbank.banking;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class BankingController {
  private final BankingService service;

  BankingController(BankingService service) {
    this.service = service;
  }

  @PostMapping("/users/{owner}/accounts")
  ResponseEntity<BankingService.Account> open(
      Authentication auth, @PathVariable UUID owner, @Valid @RequestBody Open request) {
    self(auth, owner, "account:create:any");
    var a = service.open(owner, request.accountType(), request.currency());
    return ResponseEntity.created(URI.create("/api/v1/accounts/" + a.id())).body(a);
  }

  @GetMapping("/users/{owner}/accounts")
  List<BankingService.Account> accounts(Authentication auth, @PathVariable UUID owner) {
    self(auth, owner, "account:read:any");
    return service.owner(owner);
  }

  @GetMapping("/accounts/{id}")
  BankingService.Account account(Authentication auth, @PathVariable UUID id) {
    own(auth, id, "account:read:any");
    return service.find(id);
  }

  @PatchMapping("/accounts/{id}/freeze")
  BankingService.Account freeze(Authentication a, @PathVariable UUID id) {
    authority(a, "account:freeze:any");
    return service.status(id, "FROZEN");
  }

  @PatchMapping("/accounts/{id}/unfreeze")
  BankingService.Account unfreeze(Authentication a, @PathVariable UUID id) {
    authority(a, "account:unfreeze:any");
    return service.status(id, "ACTIVE");
  }

  @PatchMapping("/accounts/{id}/close")
  BankingService.Account close(Authentication a, @PathVariable UUID id) {
    own(a, id, "account:close:any");
    return service.status(id, "CLOSED");
  }

  @PostMapping("/accounts/{id}/deposits")
  ResponseEntity<BankingService.Transaction> deposit(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody Money r) {
    own(a, id, "deposit:create:any");
    var t = service.money(id, r.amount(), r.description(), true);
    return ResponseEntity.created(URI.create("/api/v1/transactions/" + t.id())).body(t);
  }

  @PostMapping("/accounts/{id}/withdrawals")
  ResponseEntity<BankingService.Transaction> withdraw(
      Authentication a, @PathVariable UUID id, @Valid @RequestBody Money r) {
    own(a, id, "withdrawal:create:any");
    var t = service.money(id, r.amount(), r.description(), false);
    return ResponseEntity.created(URI.create("/api/v1/transactions/" + t.id())).body(t);
  }

  @GetMapping("/accounts/{id}/transactions")
  List<BankingService.Transaction> transactions(Authentication a, @PathVariable UUID id) {
    own(a, id, "transaction:read:any");
    return service.transactions(id);
  }

  @GetMapping("/transactions/{id}")
  BankingService.Transaction transaction(Authentication a, @PathVariable UUID id) {
    var t = service.transaction(id);
    own(a, t.accountId(), "transaction:read:any");
    return t;
  }

  @GetMapping("/internal/accounts/{id}")
  BankingService.Account internal(@PathVariable UUID id) {
    return service.find(id);
  }

  @PostMapping("/internal/postings")
  BankingService.Posting posting(@Valid @RequestBody Posting r) {
    return service.post(
        r.commandId(),
        r.sourceAccountId(),
        r.destinationAccountId(),
        r.sourceAmount(),
        r.destinationAmount(),
        r.reference(),
        r.description());
  }

  private void self(Authentication a, UUID user, String any) {
    if (!a.getName().equals(user.toString()) && !has(a, any))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private void own(Authentication a, UUID account, String any) {
    if (!has(a, any) && !service.owns(account, UUID.fromString(a.getName())))
      throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private void authority(Authentication a, String value) {
    if (!has(a, value)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  }

  private boolean has(Authentication a, String v) {
    return a.getAuthorities().stream().anyMatch(x -> x.getAuthority().equals(v));
  }

  record Open(@NotBlank String accountType, @NotBlank String currency) {}

  record Money(
      @NotNull @DecimalMin("0.0001") BigDecimal amount, @Size(max = 255) String description) {}

  record Posting(
      @NotNull UUID commandId,
      @NotNull UUID sourceAccountId,
      @NotNull UUID destinationAccountId,
      @NotNull @DecimalMin("0.0001") BigDecimal sourceAmount,
      @NotNull @DecimalMin("0.0001") BigDecimal destinationAmount,
      @NotBlank @Size(max = 64) String reference,
      @Size(max = 255) String description) {}
}
