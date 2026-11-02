package com.vandunxg.file_processing.fileimport.application.capability;

import java.util.List;

import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;

/**
 * Paginated job read model.
 *
 * <p>Kept out of {@code ProcessingJobRepository}: paging and filtering answer a screen's question,
 * not the aggregate's consistency needs, and the domain contract should not grow a page index.
 */
public interface ProcessingJobSearchRepository {

  Long count(ProcessingJobSearchQuery query);

  List<ProcessingJob> search(ProcessingJobSearchQuery query);
}
