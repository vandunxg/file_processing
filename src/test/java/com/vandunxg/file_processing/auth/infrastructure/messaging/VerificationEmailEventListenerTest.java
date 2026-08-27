package com.vandunxg.file_processing.auth.infrastructure.messaging;

import static org.mockito.Mockito.verify;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.application.capability.EmailSender;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationEmailEventListenerTest {

  @Mock private EmailSender emailSender;

  @Test
  void onSendVerificationEmailEvent_delegatesToEmailSenderPort() {
    VerificationEmailEventListener listener = new VerificationEmailEventListener(emailSender);
    SendVerificationEmailEvent event =
        new SendVerificationEmailEvent(
            "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");

    listener.onSendVerificationEmailEvent(MessageEnvelope.wrap(event));

    verify(emailSender)
        .sendVerificationEmail(
            "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");
  }
}
