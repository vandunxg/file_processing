package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.Locale;
import java.util.Map;

import com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom;
import com.vandunxg.common.persistence.support.SqlUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.fileimport.application.capability.ProcessingJobSearchRepository;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import org.springframework.stereotype.Repository;

/**
 * Runs the job list.
 *
 * <p>A plain bean rather than a Spring Data fragment: the read model is reached through the
 * application capability, so nothing needs it hanging off the aggregate repository. {@code count}
 * and {@code search} come from the shared paging base, which also applies the default {@code
 * createdAt desc} ordering when the caller asks for no sort.
 */
@Repository
public class JpaProcessingJobSearchRepository
    extends BaseEntityRepositoryCustom<ProcessingJob, ProcessingJobSearchQuery>
    implements ProcessingJobSearchRepository {

  public JpaProcessingJobSearchRepository() {
    super(ProcessingJob.class);
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
