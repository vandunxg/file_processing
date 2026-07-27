package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaActionLogRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.ActionLogPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogPort;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ACTION-LOG-PERSISTENCE")
public class ActionLogPersistenceAdapter implements ActionLogPort {

  private final JpaActionLogRepository jpaActionLogRepository;
  private final ActionLogPersistenceMapper actionLogPersistenceMapper;

  @Override
  @Transactional
  public void record(ActionLog actionLog) {
    jpaActionLogRepository.save(actionLogPersistenceMapper.toEntity(actionLog));
    log.debug(
        "[record] persisted action log path={} status={}",
        actionLog.getPath(),
        actionLog.getStatusCode());
  }

  @Override
  public Long count(ActionLogSearchQuery query) {
    return jpaActionLogRepository.count(query);
  }

  @Override
  public List<ActionLog> search(ActionLogSearchQuery query) {
    return actionLogPersistenceMapper.toDomain(jpaActionLogRepository.search(query));
  }
}
