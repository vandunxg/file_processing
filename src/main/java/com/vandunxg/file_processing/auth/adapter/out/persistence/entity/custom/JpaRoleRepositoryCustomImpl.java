package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.persistence.support.SqlUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import org.jspecify.annotations.NullMarked;

import java.util.Map;

@NullMarked
public class JpaRoleRepositoryCustomImpl
  extends BaseEntityRepositoryCustom<RoleEntity, RoleSearchQuery>
  implements JpaRoleRepositoryCustom {

  protected JpaRoleRepositoryCustomImpl() {
    super(RoleEntity.class);
  }

  @Override
  protected String createWhereQuery(RoleSearchQuery query,
                                    Map<String, Object> values,
                                    StringBuilder joinClause) {

    StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");

    if (StrUtils.isNotBlank(query.getKeyword())) {
      sql.append("""
        AND (E.code like :keyword)
        """);
      values.put("keyword", SqlUtils.encodeKeyword(query.getKeyword()));
    }

    if (query.getStatus() != null) {
      sql.append(" AND E.status = :status ");
      values.put("status", query.getStatus());
    }

    return sql.toString();
  }
}
