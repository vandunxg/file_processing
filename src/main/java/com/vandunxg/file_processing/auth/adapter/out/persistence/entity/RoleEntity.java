package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "role")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class RoleEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  // no domain field yet in this delivery; role hierarchy is not read/written here
  @Column(name = "role_inherited_id")
  private UUID roleInheritedId;

  @Column(name = "code", nullable = false, length = 50)
  private String code;

  // no domain field yet in this delivery
  @Column(name = "name", nullable = false, length = 100)
  private String name;

  // no domain field yet in this delivery
  @Column(name = "description", length = 1000)
  private String description;

  // no domain field yet in this delivery; safe default matches the migration's DB default
  @Column(name = "is_const", nullable = false)
  private boolean isConst = false;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private ActiveStatus status;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
