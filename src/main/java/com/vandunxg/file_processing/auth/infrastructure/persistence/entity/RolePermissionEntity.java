package com.vandunxg.file_processing.auth.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "role_permission")
@Getter
@Setter
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class RolePermissionEntity extends AuditableEntity {

  @Id private UUID id;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Enumerated(EnumType.STRING)
  @Column(name = "resource_code", nullable = false, length = 50)
  private ResourceCode resourceCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", nullable = false, length = 20)
  private Action action;

  @Column(name = "resource_group", length = 255)
  private String resourceGroup;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
