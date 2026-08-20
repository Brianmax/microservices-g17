package com.virtualbank.identity;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/security")
class SecurityAdminController {
  private final IdentityService service;

  SecurityAdminController(IdentityService service) {
    this.service = service;
  }

  @GetMapping("/roles")
  @PreAuthorize("hasAuthority('role:read:any')")
  List<RoleView> roles() {
    return service.allRoles();
  }

  @GetMapping("/permissions")
  @PreAuthorize("hasAuthority('permission:read:any')")
  List<PermissionView> permissions() {
    return service.allPermissions();
  }

  @PutMapping("/users/{userId}/roles")
  @PreAuthorize("hasAuthority('role:assign:any')")
  ResponseEntity<Void> roles(
      @PathVariable UUID userId, @Valid @RequestBody RoleAssignment request) {
    service.replaceRoles(userId, request.roles());
    return ResponseEntity.noContent().build();
  }

  record RoleAssignment(@NotEmpty Set<String> roles) {}

  record RoleView(UUID id, String code, String description, String[] permissions) {}

  record PermissionView(UUID id, String code, String description) {}
}
