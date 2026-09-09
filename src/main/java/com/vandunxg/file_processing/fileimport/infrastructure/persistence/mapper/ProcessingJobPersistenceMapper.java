package com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingAttemptEntity;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingJobEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps between the job aggregate and its rows.
 *
 * <p>The aggregate exposes no public constructor or builder, so nothing outside {@code queue} and
 * {@code reconstitute} can produce one that skips its invariants. Rebuilding therefore calls {@code
 * reconstitute} explicitly instead of being generated; because that factory takes every persisted
 * field, adding a field to the aggregate breaks this call site until the row carries it too.
 *
 * <p>The other direction is generated with {@code unmappedTargetPolicy = ERROR}, and that is load
 * bearing rather than tidiness: the repository writes back by merging this entity over the stored
 * row, so a plain column left unmapped here is merged in as {@code null} and the stored value is
 * gone -- {@code deletedAt} being the one that would quietly resurrect a retired job.
 *
 * <p>The creation audit is the exception that needs no such care: {@code AuditableEntity} maps
 * {@code created_at} and {@code created_by} as {@code updatable = false}, so no update reaches them
 * whatever this mapper says. They are carried across anyway, because the insert path reads them and
 * because a field quietly excused is how the next one gets forgotten.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public abstract class ProcessingJobPersistenceMapper {

  public ProcessingJob toDomain(ProcessingJobEntity entity) {
    if (entity == null) {
      return null;
    }
    return ProcessingJob.reconstitute(
        entity.getId(),
        entity.getImportFileId(),
        entity.getOwnerId(),
        entity.getStatus(),
        entity.getProcessedRows(),
        entity.getValidRows(),
        entity.getInvalidRows(),
        entity.getInsertedRows(),
        entity.getUpdatedRows(),
        entity.getTotalRows(),
        entity.getProgressPercent(),
        entity.getCurrentAttempt(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getHeartbeatAt(),
        entity.getErrorReportKey(),
        entity.getErrorCode(),
        entity.getErrorSummary(),
        entity.getNextAttemptTrigger(),
        entity.getDeletedAt(),
        entity.getVersion(),
        toDomainAttempts(entity.getAttempts()),
        entity.getCreatedBy(),
        entity.getCreatedAt(),
        entity.getLastModifiedBy(),
        entity.getLastModifiedAt());
  }

  public List<ProcessingJob> toDomain(List<ProcessingJobEntity> entities) {
    return entities.stream().map(this::toDomain).toList();
  }

  private List<ProcessingAttempt> toDomainAttempts(List<ProcessingAttemptEntity> entities) {
    return entities.stream().map(this::toDomainAttempt).toList();
  }

  private ProcessingAttempt toDomainAttempt(ProcessingAttemptEntity entity) {
    return ProcessingAttempt.reconstitute(
        entity.getId(),
        entity.getJobId(),
        entity.getAttemptNumber(),
        entity.getTrigger(),
        entity.getStatus(),
        entity.getStartedAt(),
        entity.getFinishedAt(),
        entity.getProcessedRows(),
        entity.getValidRows(),
        entity.getInvalidRows(),
        entity.getInsertedRows(),
        entity.getUpdatedRows(),
        entity.getErrorCode(),
        entity.getErrorSummary(),
        entity.getDeletedAt(),
        entity.getCreatedBy(),
        entity.getCreatedAt(),
        entity.getLastModifiedBy(),
        entity.getLastModifiedAt());
  }

  public abstract ProcessingJobEntity toEntity(ProcessingJob domain);

  protected abstract ProcessingAttemptEntity toEntity(ProcessingAttempt domain);
}
