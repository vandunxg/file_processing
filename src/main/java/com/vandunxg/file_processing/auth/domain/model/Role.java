package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
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
public class Role extends AuditableDomain {

  private UUID id;
  private String code;
  private ActiveStatus status;
  private Instant deletedAt;

  public boolean isActive() {
    return status == ActiveStatus.ACTIVE && !isDeleted();
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }
}
