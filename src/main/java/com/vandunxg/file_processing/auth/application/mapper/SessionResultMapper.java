package com.vandunxg.file_processing.auth.application.mapper;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.result.SessionResult;
import com.vandunxg.file_processing.auth.domain.model.Session;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps refresh sessions onto the application result. {@code currentSessionId} is the caller's own
 * session, so the list can mark which entry the caller is using right now.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface SessionResultMapper {

  @Mapping(target = "sessionId", source = "session.id")
  @Mapping(target = "createdAt", source = "session.issuedAt")
  @Mapping(target = "userAgent", source = "session.userAgent")
  @Mapping(target = "lastUsedAt", source = "session.lastUsedAt")
  @Mapping(target = "expiresAt", source = "session.expiresAt")
  // Guarded on session too: MapStruct only skips the whole mapping when *both* sources are null,
  // so an expression that dereferences session directly throws for (null, non-null).
  @Mapping(
      target = "current",
      expression =
          "java(session != null && currentSessionId != null"
              + " && currentSessionId.equals(session.getId()))")
  SessionResult toResult(Session session, UUID currentSessionId);

  default List<SessionResult> toResults(List<Session> sessions, UUID currentSessionId) {
    return sessions.stream().map(session -> toResult(session, currentSessionId)).toList();
  }
}
