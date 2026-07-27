package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.in.web.ActionLogController.ActionLogResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ActionLogSearchRequest;
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
