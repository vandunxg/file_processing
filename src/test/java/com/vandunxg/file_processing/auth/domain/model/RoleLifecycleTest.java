package com.vandunxg.file_processing.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RoleLifecycleTest {

  @Test
  void createNormalizesCodeAndCreatesAnActiveMutableRole() {
    Role role = Role.create(" auditor ", "Audit Reader", "Reads audit logs", Instant.now());

    assertThat(role.getCode()).isEqualTo("AUDITOR");
    assertThat(role.isConst()).isFalse();
    assertThat(role.isActive()).isTrue();
  }

  @Test
  void inheritanceCannotReferenceTheRoleItself() {
    Role role = Role.create("AUDITOR", "Audit Reader", null, Instant.now());

    assertThatThrownBy(() -> role.setInheritedRole(role.getId()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void systemRolesCannotChangeCodeOrInheritance() {
    Role role =
        Role.builder()
            .id(UUID.randomUUID())
            .code("ADMIN")
            .name("Administrator")
            .isConst(true)
            .status(ActiveStatus.ACTIVE)
            .build();

    assertThatThrownBy(() -> role.update("AUDITOR", "Administrator", null))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> role.setInheritedRole(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class);
  }
}
