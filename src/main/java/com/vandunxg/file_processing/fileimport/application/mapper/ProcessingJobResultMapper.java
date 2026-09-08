package com.vandunxg.file_processing.fileimport.application.mapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobAction;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobProgressResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobSummaryResult;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import org.mapstruct.Context;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Assembles a job view from the job and its file.
 *
 * <p>The storage key, bucket, checksum and technical error code are deliberately absent: a client
 * has no use for them and they describe where the data lives.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface ProcessingJobResultMapper {

  @Mapping(target = "jobId", source = "job.id")
  @Mapping(target = "fileId", source = "file.id")
  @Mapping(target = "ownerId", source = "job.ownerId")
  @Mapping(target = "originalFilename", source = "file.originalFilename")
  @Mapping(target = "sizeBytes", source = "file.sizeBytes")
  @Mapping(target = "createdAt", source = "job.createdAt")
  @Mapping(target = "errorReportAvailable", expression = "java(job.hasErrorReport())")
  @Mapping(target = "availableActions", expression = "java(availableActions(job, file, now))")
  @Mapping(target = "attempts", source = "job.attempts")
  ProcessingJobResult toResult(ProcessingJob job, ImportFile file, @Context Instant now);

  /**
   * The actions the caller could take right now.
   *
   * <p>Retry needs the original, so an expired retention window removes it even though the job
   * itself is still in a retryable state.
   */
  default List<ProcessingJobAction> availableActions(
      ProcessingJob job, ImportFile file, Instant now) {
    List<ProcessingJobAction> actions = new ArrayList<>(3);
    if (job.isCancellable()) {
      actions.add(ProcessingJobAction.CANCEL);
    }
    if (job.isRetryable() && !file.isExpired(now)) {
      actions.add(ProcessingJobAction.RETRY);
    }
    if (job.hasErrorReport()) {
      actions.add(ProcessingJobAction.DOWNLOAD_ERROR_REPORT);
    }
    return List.copyOf(actions);
  }

  ProcessingJobResult.AttemptResult toAttemptResult(ProcessingAttempt attempt);

  @Mapping(target = "jobId", source = "job.id")
  @Mapping(target = "fileId", source = "file.id")
  @Mapping(target = "ownerId", source = "job.ownerId")
  @Mapping(target = "originalFilename", source = "file.originalFilename")
  @Mapping(target = "sizeBytes", source = "file.sizeBytes")
  @Mapping(target = "createdAt", source = "job.createdAt")
  @Mapping(target = "errorReportAvailable", expression = "java(job.hasErrorReport())")
  ProcessingJobSummaryResult toSummaryResult(ProcessingJob job, ImportFile file);

  @Mapping(target = "jobId", source = "id")
  ProcessingJobProgressResult toProgressResult(ProcessingJob job);
}
