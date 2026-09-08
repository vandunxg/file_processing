package com.vandunxg.file_processing.fileimport.application.query;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.persistence.query.PagingQuery;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/**
 * Filters for the job list. The inherited {@code keyword} matches the original filename.
 *
 * <p>{@code ownerId} is not a caller preference. The application overwrites it with the requester's
 * own id unless the requester may act on any owner, so naming somebody else's id can never widen
 * what a caller sees.
 */
@Getter
@Setter
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ProcessingJobSearchQuery extends PagingQuery {

  private JobStatus status;
  private UUID ownerId;
  private Instant createdFrom;
  private Instant createdTo;
}
