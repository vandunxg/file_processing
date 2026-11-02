package com.vandunxg.file_processing.fileimport.api.mapper;

import com.vandunxg.file_processing.fileimport.api.dto.request.ProcessingJobSearchRequest;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Turns the transport's query parameters into the application's search query. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ProcessingJobWebMapper {

  ProcessingJobSearchQuery toQuery(ProcessingJobSearchRequest request);
}
