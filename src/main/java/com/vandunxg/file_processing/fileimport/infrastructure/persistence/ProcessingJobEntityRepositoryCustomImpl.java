package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.Locale;
import java.util.Map;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.persistence.support.SqlUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingJobEntity;

/**
 * Runs the job list.
 *
 * <p>{@code count} and {@code search} come from the shared paging base, which also applies the
 * default {@code createdAt desc} ordering when the caller asks for no sort.
 */
public class ProcessingJobEntityRepositoryCustomImpl
    extends BaseEntityRepositoryCustom<ProcessingJobEntity, ProcessingJobSearchQuery>
    implements ProcessingJobEntityRepositoryCustom {

  protected ProcessingJobEntityRepositoryCustomImpl() {
    super(ProcessingJobEntity.class);
  }

  @Override
  protected String createWhereQuery(
      ProcessingJobSearchQuery query, Map<String, Object> values, StringBuilder joinClause) {
    StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");
    if (query.getOwnerId() != null) {
      sql.append(" AND E.ownerId = :ownerId ");
      values.put("ownerId", query.getOwnerId());
    }
    if (query.getStatus() != null) {
      sql.append(" AND E.status = :status ");
      values.put("status", query.getStatus());
    }
    if (query.getCreatedFrom() != null) {
      sql.append(" AND E.createdAt >= :createdFrom ");
      values.put("createdFrom", query.getCreatedFrom());
    }
    if (query.getCreatedTo() != null) {
      sql.append(" AND E.createdAt <= :createdTo ");
      values.put("createdTo", query.getCreatedTo());
    }
    // The file is what the row describes, so a job whose file has been retired is not listable --
    // and a subquery rather than a join keeps count and search agreeing without risking a
    // multiplied row. The job holds a plain file id, so there is no association to navigate.
    sql.append(
        " AND EXISTS (SELECT 1 FROM ImportFileEntity F"
            + " WHERE F.id = E.importFileId AND F.deletedAt is null ");
    if (StrUtils.isNotBlank(query.getKeyword())) {
      sql.append(" AND lower(F.originalFilename) like :keyword ");
      values.put("keyword", SqlUtils.encodeKeyword(query.getKeyword().toLowerCase(Locale.ROOT)));
    }
    sql.append(") ");
    return sql.toString();
  }
}
