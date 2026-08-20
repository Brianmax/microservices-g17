package com.virtualbank.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/customers")
class CustomerController {
  private final CustomerRepository repository;

  CustomerController(CustomerRepository repository) {
    this.repository = repository;
  }

  @PostMapping("/internal")
  @Transactional
  ResponseEntity<Response> createInternal(@Valid @RequestBody InternalCreate request) {
    String email = normalize(request.email());
    if (repository.existsByEmailIgnoreCase(email))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate email");
    Customer saved =
        repository.save(
            new Customer(
                request.id(), request.firstName().trim(), request.lastName().trim(), email));
    return ResponseEntity.created(URI.create("/api/v1/customers/" + saved.getId()))
        .body(response(saved));
  }

  @DeleteMapping("/internal/{id}")
  @Transactional
  ResponseEntity<Void> rollback(@PathVariable UUID id) {
    repository.deleteById(id);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/{id}")
  @PreAuthorize(
      "hasAuthority('user:read:any') or (hasAuthority('user:read:self') and authentication.name == #id.toString())")
  Response get(@PathVariable UUID id) {
    return response(find(id));
  }

  @PutMapping("/{id}")
  @PreAuthorize(
      "hasAuthority('user:update:any') or (hasAuthority('user:update:self') and authentication.name == #id.toString())")
  @Transactional
  Response update(@PathVariable UUID id, @Valid @RequestBody Update request) {
    Customer c = find(id);
    String email = normalize(request.email());
    if (repository.existsByEmailIgnoreCaseAndIdNot(email, id))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate email");
    c.update(request.firstName().trim(), request.lastName().trim(), email);
    return response(c);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('user:deactivate:any')")
  @Transactional
  ResponseEntity<Void> deactivate(@PathVariable UUID id) {
    find(id).deactivate();
    return ResponseEntity.noContent().build();
  }

  private Customer find(UUID id) {
    return repository
        .findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found"));
  }

  private String normalize(String value) {
    return value.trim().toLowerCase(Locale.ROOT);
  }

  private Response response(Customer c) {
    return new Response(
        c.getId(),
        c.getFirstName(),
        c.getLastName(),
        c.getEmail(),
        c.getStatus(),
        c.getCreatedAt(),
        c.getUpdatedAt());
  }

  record InternalCreate(
      @NotNull UUID id,
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @Email @NotBlank @Size(max = 320) String email) {}

  record Update(
      @NotBlank @Size(max = 100) String firstName,
      @NotBlank @Size(max = 100) String lastName,
      @Email @NotBlank @Size(max = 320) String email) {}

  record Response(
      UUID id,
      String firstName,
      String lastName,
      String email,
      Customer.Status status,
      java.time.Instant createdAt,
      java.time.Instant updatedAt) {}
}
