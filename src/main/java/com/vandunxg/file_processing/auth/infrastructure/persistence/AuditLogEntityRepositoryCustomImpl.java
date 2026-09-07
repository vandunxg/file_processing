package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.AuditLogEntity;

public class AuditLogEntityRepositoryCustomImpl
    extends BaseEntityRepositoryCustom<AuditLogEntity, AuditLogSearchQuery>
    implements AuditLogEntityRepositoryCustom {

  protected AuditLogEntityRepositoryCustomImpl() {
    super(AuditLogEntity.class);
  }

  @Override
  protected String createWhereQuery(
      AuditLogSearchQuery query, Map<String, Object> values, StringBuilder joinClause) {
    StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");
    if (StrUtils.isNotBlank(query.getKeyword())) {
      appendKeywordFilter(query.getKeyword(), values, sql);
    }
    if (query.getDomain() != null) {
      sql.append(" AND E.domain = :domain ");
      values.put("domain", query.getDomain());
    }
    if (query.getOperation() != null) {
      sql.append(" AND E.operation = :operation ");
      values.put("operation", query.getOperation());
    }
    if (query.getChangedBy() != null) {
      sql.append(" AND E.changedBy = :changedBy ");
      values.put("changedBy", query.getChangedBy());
    }
    return sql.toString();
  }

  private static void appendKeywordFilter(
      String keyword, Map<String, Object> values, StringBuilder sql) {
    String normalized = keyword.toLowerCase(Locale.ROOT);
    List<AuditLogDomain> domains = matchingEnums(AuditLogDomain.values(), normalized);
    List<OperationType> operations = matchingEnums(OperationType.values(), normalized);
    UUID id = parseUuid(keyword);
    if (domains.isEmpty() && operations.isEmpty() && id == null) {
      sql.append(" AND 1 = 0 ");
      return;
    }
    sql.append(" AND (");
    boolean needsOr = false;
    if (!domains.isEmpty()) {
      sql.append("E.domain in :keywordDomains");
      values.put("keywordDomains", domains);
      needsOr = true;
    }
    if (!operations.isEmpty()) {
      if (needsOr) {
        sql.append(" OR ");
      }
      sql.append("E.operation in :keywordOperations");
      values.put("keywordOperations", operations);
      needsOr = true;
    }
    if (id != null) {
      if (needsOr) {
        sql.append(" OR ");
      }
      sql.append("E.objectId = :keywordId OR E.changedBy = :keywordId");
      values.put("keywordId", id);
    }
    sql.append(") ");
  }

  private static <E extends Enum<E>> List<E> matchingEnums(E[] values, String keyword) {
    return Arrays.stream(values)
        .filter(value -> value.name().toLowerCase(Locale.ROOT).contains(keyword))
        .toList();
  }

  private static UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return null;
    }
  }
}
