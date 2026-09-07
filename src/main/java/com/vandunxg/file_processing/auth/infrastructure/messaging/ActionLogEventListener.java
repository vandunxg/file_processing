package com.vandunxg.file_processing.auth.infrastructure.messaging;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.domain.ActionLogRepository;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ACTION-LOG-LISTENER")
public class ActionLogEventListener {

  private final ActionLogRepository actionLogRepository;

  @RabbitListener(queues = "${app.auth.amqp.queue.action-log}")
  public void onActionLogEvent(MessageEnvelope<@NonNull ActionLog> envelope) {
    ActionLog actionLog = envelope.payload();
    log.debug(
        "[onActionLogEvent] received action log event path={} status={}",
        actionLog.getPath(),
        actionLog.getStatusCode());
    actionLogRepository.record(actionLog);
  }
}
