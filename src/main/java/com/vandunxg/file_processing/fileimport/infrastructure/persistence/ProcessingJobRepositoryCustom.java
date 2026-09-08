package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;

/** The parts of the job repository that Spring Data cannot derive from a method name. */
public interface ProcessingJobRepositoryCustom {

  Optional<ProcessingJob> claimNextQueued(Instant now);
}
