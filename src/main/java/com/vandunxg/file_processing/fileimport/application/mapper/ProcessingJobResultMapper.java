package com.vandunxg.file_processing.fileimport.application.mapper;

import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobResult;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
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
  @Mapping(target = "originalFilename", source = "file.originalFilename")
  @Mapping(target = "sizeBytes", source = "file.sizeBytes")
  @Mapping(target = "createdAt", source = "job.createdAt")
  @Mapping(target = "errorReportAvailable", expression = "java(job.getErrorReportKey() != null)")
  @Mapping(target = "attempts", source = "job.attempts")
  ProcessingJobResult toResult(ProcessingJob job, ImportFile file);

  ProcessingJobResult.AttemptResult toAttemptResult(ProcessingAttempt attempt);
}
