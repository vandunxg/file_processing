package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.Locale;
import java.util.Map;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.persistence.support.SqlUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.ActionLogEntity;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class ActionLogEntityRepositoryCustomImpl
    extends BaseEntityRepositoryCustom<ActionLogEntity, ActionLogSearchQuery>
    implements ActionLogEntityRepositoryCustom {

  protected ActionLogEntityRepositoryCustomImpl() {
    super(ActionLogEntity.class);
  }

  @Override
  protected String createWhereQuery(
      ActionLogSearchQuery query, Map<String, Object> values, StringBuilder joinClause) {
    StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");
    if (StrUtils.isNotBlank(query.getKeyword())) {
      sql.append(
          " AND (lower(E.username) like :keyword OR lower(E.apiDoc) like :keyword OR lower(E.errorMessage) like :keyword) ");
      values.put("keyword", contains(query.getKeyword()));
    }
    appendContainsFilter(sql, values, "username", "E.username", query.getUsername());
    appendContainsFilter(sql, values, "apiDoc", "E.apiDoc", query.getApiDoc());
    appendContainsFilter(sql, values, "errorMessage", "E.errorMessage", query.getErrorMessage());
    if (StrUtils.isNotBlank(query.getRequestMethod())) {
      sql.append(" AND E.requestMethod = :requestMethod ");
      values.put("requestMethod", query.getRequestMethod());
    }
    if (query.getStartTimeFrom() != null) {
      sql.append(" AND E.startTime >= :startTimeFrom ");
      values.put("startTimeFrom", query.getStartTimeFrom());
    }
    if (query.getStartTimeTo() != null) {
      sql.append(" AND E.startTime <= :startTimeTo ");
      values.put("startTimeTo", query.getStartTimeTo());
    }
    return sql.toString();
  }

  private static void appendContainsFilter(
      StringBuilder sql, Map<String, Object> values, String name, String field, String value) {
    if (StrUtils.isNotBlank(value)) {
      sql.append(" AND lower(").append(field).append(") like :").append(name).append(' ');
      values.put(name, contains(value));
    }
  }

  private static String contains(String value) {
    return SqlUtils.encodeKeyword(value.toLowerCase(Locale.ROOT));
  }
}
