package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import java.util.Locale;
import java.util.Map;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.persistence.support.SqlUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;

public class JpaUserRepositoryCustomImpl
    extends BaseEntityRepositoryCustom<UserEntity, UserSearchQuery>
    implements JpaUserRepositoryCustom {

  protected JpaUserRepositoryCustomImpl() {
    super(UserEntity.class);
  }

  @Override
  protected String createWhereQuery(
      UserSearchQuery query, Map<String, Object> values, StringBuilder joinClause) {
    StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");
    if (StrUtils.isNotBlank(query.getKeyword())) {
      sql.append(
          " AND (E.normalizedUsername like :keyword OR E.normalizedEmail like :keyword OR lower(E.displayName) like :keyword) ");
      values.put("keyword", SqlUtils.encodeKeyword(query.getKeyword().toLowerCase(Locale.ROOT)));
    }
    if (query.getStatus() != null) {
      sql.append(" AND E.status = :status ");
      values.put("status", query.getStatus());
    }
    return sql.toString();
  }
}
