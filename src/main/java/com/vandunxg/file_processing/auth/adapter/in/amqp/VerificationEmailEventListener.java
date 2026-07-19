package com.vandunxg.file_processing.auth.adapter.in.amqp;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-LISTENER")
public class VerificationEmailEventListener {

  private final EmailSenderPort emailSenderPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.verification-email}")
  public void onSendVerificationEmailEvent(MessageEnvelope<SendVerificationEmailEvent> envelope) {
    SendVerificationEmailEvent event = envelope.payload();
    // Never log event.verificationLink(): it carries the raw opaque token.
    log.debug("[onSendVerificationEmailEvent] received verification email event");
    emailSenderPort.sendVerificationEmail(
        event.toEmail(), event.displayName(), event.verificationLink());
  }
}
