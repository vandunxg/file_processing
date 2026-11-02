package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingJobEntity;

/** The job list's dynamic query, which Spring Data cannot derive from a method name. */
public interface ProcessingJobEntityRepositoryCustom {

  Long count(ProcessingJobSearchQuery query);

  List<ProcessingJobEntity> search(ProcessingJobSearchQuery query);
}
