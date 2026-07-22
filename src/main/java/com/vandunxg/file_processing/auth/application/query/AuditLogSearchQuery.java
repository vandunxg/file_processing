package com.vandunxg.file_processing.auth.application.query;

import java.util.UUID;

import com.vandunxg.common.persistence.query.PagingQuery;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class AuditLogSearchQuery extends PagingQuery {
  private AuditLogDomain domain;
  private OperationType operation;
  private UUID changedBy;
}
