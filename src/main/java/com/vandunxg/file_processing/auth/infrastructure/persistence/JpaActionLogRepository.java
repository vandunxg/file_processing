package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.capability.ActionLogSearchRepository;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.ActionLogRepository;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.ActionLogPersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ACTION-LOG-PERSISTENCE")
public class JpaActionLogRepository implements ActionLogRepository, ActionLogSearchRepository {

  private final ActionLogEntityRepository actionLogEntityRepository;
  private final ActionLogPersistenceMapper actionLogPersistenceMapper;

  @Override
  @Transactional
  public void record(ActionLog actionLog) {
    actionLogEntityRepository.save(actionLogPersistenceMapper.toEntity(actionLog));
    log.debug(
        "[record] persisted action log path={} status={}",
        actionLog.getPath(),
        actionLog.getStatusCode());
  }

  @Override
  public Long count(ActionLogSearchQuery query) {
    return actionLogEntityRepository.count(query);
  }

  @Override
  public List<ActionLog> search(ActionLogSearchQuery query) {
    return actionLogPersistenceMapper.toDomain(actionLogEntityRepository.search(query));
  }
}
