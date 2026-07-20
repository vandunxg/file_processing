package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

import com.vandunxg.common.models.enums.Action;
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
@EqualsAndHashCode(of = {"roleId", "resourceCode", "action"})
public class RolePermission {

  private UUID id;
  private UUID roleId;
  private ResourceCode resourceCode;
  private Action action;
  private String resourceGroup;
  private Instant deletedAt;

  public static RolePermission grant(UUID roleId, ResourceCode resourceCode, Action action) {
    if (roleId == null || resourceCode == null || action == null) {
      throw new IllegalArgumentException("Role permission is incomplete");
    }
    return RolePermission.builder()
        .id(IdUtils.nextId())
        .roleId(roleId)
        .resourceCode(resourceCode)
        .action(action)
        .resourceGroup(resourceCode.getGroup())
        .build();
  }

  public String authority() {
    return resourceCode.name().toLowerCase(Locale.ROOT)
        + ":"
        + action.name().toLowerCase(Locale.ROOT);
  }
}
