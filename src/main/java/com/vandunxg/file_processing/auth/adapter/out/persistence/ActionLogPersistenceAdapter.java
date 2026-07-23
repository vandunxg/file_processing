package com.vandunxg.file_processing.auth.adapter.out.persistence;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.ActionLogEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaActionLogRepository;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogPort;
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

  @Override
  @Transactional
  public void record(ActionLog actionLog) {
    jpaActionLogRepository.save(toEntity(actionLog));
    log.debug(
        "[record] persisted action log path={} status={}",
        actionLog.getPath(),
        actionLog.getStatusCode());
  }

  private static ActionLogEntity toEntity(ActionLog log) {
    ActionLogEntity entity = new ActionLogEntity();
    entity.setId(log.getId());
    entity.setUserId(log.getUserId());
    entity.setUsername(log.getUsername());
    entity.setStartTime(log.getStartTime());
    entity.setEndTime(log.getEndTime());
    entity.setDuration(log.getDuration());
    entity.setPath(log.getPath());
    entity.setApiDoc(log.getApiDoc());
    entity.setRequestMethod(log.getRequestMethod());
    entity.setIpAddress(log.getIpAddress());
    entity.setUserAgent(log.getUserAgent());
    entity.setRequestData(log.getRequestData());
    entity.setStatusCode(log.getStatusCode());
    entity.setErrorMessage(log.getErrorMessage());
    entity.setRequestParam(log.getRequestParam());
    return entity;
  }
}
