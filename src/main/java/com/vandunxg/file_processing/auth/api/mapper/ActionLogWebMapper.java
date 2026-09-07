package com.vandunxg.file_processing.auth.api.mapper;

import java.util.List;

import com.vandunxg.file_processing.auth.api.ActionLogController.ActionLogResponse;
import com.vandunxg.file_processing.auth.api.dto.request.ActionLogSearchRequest;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ActionLogWebMapper {

  ActionLogSearchQuery toQuery(ActionLogSearchRequest request);

  ActionLogResponse toResponse(ActionLog actionLog);

  List<ActionLogResponse> toResponse(List<ActionLog> actionLogs);
}
