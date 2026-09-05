package com.vandunxg.file_processing.auth.api.mapper;

import java.util.List;

import com.vandunxg.file_processing.auth.api.AuditLogController.AuditLogResponse;
import com.vandunxg.file_processing.auth.api.dto.request.AuditLogSearchRequest;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuditLogWebMapper {

  AuditLogSearchQuery toQuery(AuditLogSearchRequest request);

  AuditLogResponse toResponse(AuditLog audit);

  List<AuditLogResponse> toResponse(List<AuditLog> audits);

  default String map(AuditLogDomain domain) {
    return domain == null ? null : domain.name();
  }

  default String map(OperationType operation) {
    return operation == null ? null : operation.name();
  }
}
