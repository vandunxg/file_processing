package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.vandunxg.file_processing.auth.application.port.in.ListSessionsUseCase;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.application.result.SessionResult;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-LIST-SESSIONS")
public class ListSessionsService implements ListSessionsUseCase {

  private final SessionRepositoryPort sessionRepositoryPort;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public List<SessionResult> list(ListSessionsQuery query) {
    Instant now = Instant.now(clock);
    List<Session> sessions = sessionRepositoryPort.listActiveByUser(query.getUserId(), now);
    log.debug("[list] userId={} count={}", query.getUserId(), sessions.size());
    return sessions.stream().map(s -> toResult(s, query.getCurrentSessionId())).toList();
  }

  private static SessionResult toResult(Session session, java.util.UUID currentSid) {
    return SessionResult.builder()
        .sessionId(session.getId())
        .userAgent(session.getUserAgent())
        .createdAt(session.getIssuedAt())
        .lastUsedAt(session.getLastUsedAt())
        .expiresAt(session.getExpiresAt())
        .current(currentSid != null && currentSid.equals(session.getId()))
        .build();
  }
}
