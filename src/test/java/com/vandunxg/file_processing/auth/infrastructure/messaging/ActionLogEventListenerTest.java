package com.vandunxg.file_processing.auth.infrastructure.messaging;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.domain.ActionLogRepository;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogEventListenerTest {

  @Mock private ActionLogRepository actionLogRepository;

  @Test
  void onActionLogEvent_delegatesToActionLogPort() {
    ActionLogEventListener listener = new ActionLogEventListener(actionLogRepository);
    Instant now = Instant.now();
    ActionLog actionLog =
        ActionLog.builder()
            .id(UUID.randomUUID())
            .username("operator")
            .startTime(now)
            .endTime(now)
            .duration(0L)
            .path("/api/v1/customers")
            .requestMethod("POST")
            .statusCode(500)
            .build();

    listener.onActionLogEvent(MessageEnvelope.wrap(actionLog));

    verify(actionLogRepository).record(actionLog);
  }
}
