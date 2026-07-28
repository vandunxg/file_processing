package com.vandunxg.file_processing.auth.adapter.in.amqp;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogPort;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogEventListenerTest {

  @Mock private ActionLogPort actionLogPort;

  @Test
  void onActionLogEvent_delegatesToActionLogPort() {
    ActionLogEventListener listener = new ActionLogEventListener(actionLogPort);
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

    verify(actionLogPort).record(actionLog);
  }
}
