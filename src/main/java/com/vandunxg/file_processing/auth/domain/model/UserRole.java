package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
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
public class UserRole extends AuditableDomain {

  private UUID id;
  private UUID userId;
  private UUID roleId;
  private Instant deletedAt;

  public UserRole(UUID userId, UUID roleId) {
    this.id = IdUtils.nextId();
    this.userId = userId;
    this.roleId = roleId;
  }
}
