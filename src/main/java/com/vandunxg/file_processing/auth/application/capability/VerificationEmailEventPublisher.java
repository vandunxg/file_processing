package com.vandunxg.file_processing.auth.application.capability;

public interface VerificationEmailEventPublisher {

  void publish(String toEmail, String displayName, String verificationLink);
}
