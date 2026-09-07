package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.vandunxg.file_processing.auth.application.mapper.SessionResultMapper;
import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.application.result.SessionResult;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-LIST-SESSIONS")
public class SessionQueryService {

  private final SessionRepository sessionRepository;
  private final SessionResultMapper sessionResultMapper;
  private final Clock clock;

  @Transactional(readOnly = true)
  public List<SessionResult> list(ListSessionsQuery query) {
    Instant now = Instant.now(clock);
    List<Session> sessions = sessionRepository.listActiveByUser(query.userId(), now);
    log.debug("[list] userId={} count={}", query.userId(), sessions.size());
    return sessionResultMapper.toResults(sessions, query.currentSessionId());
  }
}
