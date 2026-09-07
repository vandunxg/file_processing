package com.vandunxg.file_processing.auth.application.capability;

public interface EmailSender {

  void sendVerificationEmail(String toEmail, String displayName, String verificationLink);

  void sendPasswordResetEmail(String toEmail, String displayName, String resetLink);
}
