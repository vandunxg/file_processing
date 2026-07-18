package com.vandunxg.file_processing.auth.application.port.out;

public interface VerificationEmailEventPublisherPort {

  void publish(String toEmail, String displayName, String verificationLink);
}
