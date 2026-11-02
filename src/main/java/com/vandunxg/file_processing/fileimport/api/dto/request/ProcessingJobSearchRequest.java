package com.vandunxg.file_processing.fileimport.api.dto.request;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.dto.request.PagingRequest;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * Query parameters of the job list.
 *
 * <p>The inherited page size defaults to a wider page than this endpoint wants, so it is narrowed
 * here and bounded: a page is one database round trip that also loads a file per row, and an
 * unbounded {@code pageSize} would let one request ask for the whole table. The bounds are
 * rejections rather than silent clamps, so a caller asking for 1000 rows learns it did not get
 * them.
 */
@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public class ProcessingJobSearchRequest extends PagingRequest {

  /** Rows per page a caller may ask for. */
  private static final int MAX_PAGE_SIZE = 100;

  private static final int DEFAULT_JOB_PAGE_SIZE = 20;

  public ProcessingJobSearchRequest() {
    setPageSize(DEFAULT_JOB_PAGE_SIZE);
  }

  @Schema(description = "Filter by job status", example = "PROCESSING")
  private JobStatus status;

  @Schema(
      description =
          "Filter by the owner of the import. Ignored unless the caller may act on any"
              + " owner, in which case omitting it lists every owner.")
  private UUID ownerId;

  @Schema(
      description = "Inclusive lower bound on job creation time",
      example = "2026-09-01T00:00:00Z")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant createdFrom;

  @Schema(
      description = "Inclusive upper bound on job creation time",
      example = "2026-09-30T00:00:00Z")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant createdTo;

  @Override
  @Min(1)
  @Max(MAX_PAGE_SIZE)
  public int getPageSize() {
    return super.getPageSize();
  }

  /** Pages are one-based; a zero would ask the paging base for a negative offset. */
  @Override
  @Min(1)
  public int getPageIndex() {
    return super.getPageIndex();
  }
}
