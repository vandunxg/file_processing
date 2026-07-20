package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class Role extends AuditableDomain {

  private UUID id;
  private UUID roleInheritedId;
  private String code;
  private String name;
  private String description;
  private boolean isConst;
  private ActiveStatus status;
  @Builder.Default private Set<RolePermission> permissions = Set.of();
  private Instant deletedAt;
  private Long version;

  public static Role create(String code, String name, String description, Instant now) {
    if (code == null || code.isBlank() || name == null || name.isBlank() || now == null) {
      throw new IllegalArgumentException("Role code, name, and time are required");
    }
    return Role.builder()
        .id(IdUtils.nextId())
        .code(code.trim().toUpperCase(Locale.ROOT))
        .name(name.trim())
        .description(description == null || description.isBlank() ? null : description.trim())
        .status(ActiveStatus.ACTIVE)
        .isConst(false)
        .build();
  }

  public void update(String code, String name, String description) {
    String normalizedCode = code == null ? null : code.trim().toUpperCase(Locale.ROOT);
    if (normalizedCode == null || normalizedCode.isBlank() || name == null || name.isBlank()) {
      throw new IllegalArgumentException("Role code and name are required");
    }
    if (isConst && !this.code.equals(normalizedCode)) {
      throw new IllegalStateException("System role code cannot change");
    }
    this.code = normalizedCode;
    this.name = name.trim();
    this.description = description == null || description.isBlank() ? null : description.trim();
  }

  public void setInheritedRole(UUID inheritedRoleId) {
    if (isConst) {
      throw new IllegalStateException("System roles cannot inherit");
    }
    if (id.equals(inheritedRoleId)) {
      throw new IllegalArgumentException("A role cannot inherit itself");
    }
    this.roleInheritedId = inheritedRoleId;
  }

  public void activate() {
    this.status = ActiveStatus.ACTIVE;
  }

  public void inactivate() {
    this.status = ActiveStatus.INACTIVE;
  }

  public void delete(Instant now) {
    if (isConst) {
      throw new IllegalStateException("System roles cannot be deleted");
    }
    this.deletedAt = now;
  }

  /**
   * Reconstitutes permissions after a persistence load; only the repository adapter should call
   * this.
   */
  public void enrichPermissions(Set<RolePermission> permissions) {
    this.permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }

  public boolean isActive() {
    return status == ActiveStatus.ACTIVE && !isDeleted();
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
