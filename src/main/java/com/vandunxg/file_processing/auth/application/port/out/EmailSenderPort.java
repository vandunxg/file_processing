package com.vandunxg.file_processing.auth.application.port.out;

public interface EmailSenderPort {

  void sendVerificationEmail(String toEmail, String displayName, String verificationLink);
}
