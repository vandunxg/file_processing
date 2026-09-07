package com.vandunxg.file_processing.auth.infrastructure.email;

import com.vandunxg.common.email.MailService;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.capability.EmailSender;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL")
public class MailServiceEmailSender implements EmailSender {

  private static final String VERIFICATION_SUBJECT = "Verify your email address";
  private static final String PASSWORD_RESET_SUBJECT = "Reset your password";

  private final MailService mailService;

  @Override
  public void sendVerificationEmail(String toEmail, String displayName, String verificationLink) {
    String content = buildHtmlContent(displayName, verificationLink);
    try {
      mailService.sendHtmlMail(toEmail, VERIFICATION_SUBJECT, content);
    } catch (MessagingException e) {
      // Never log verificationLink here: it carries the raw opaque token.
      log.error(
          "[sendVerificationEmail] failed to send verification email toEmail={}",
          StrUtils.emailFormat(toEmail),
          e);
      throw new RuntimeException("Failed to send verification email", e);
    }
  }

  @Override
  public void sendPasswordResetEmail(String toEmail, String displayName, String resetLink) {
    try {
      mailService.sendHtmlMail(
          toEmail, PASSWORD_RESET_SUBJECT, buildResetHtmlContent(displayName, resetLink));
    } catch (MessagingException e) {
      log.error(
          "[sendPasswordResetEmail] failed to send password reset email toEmail={}",
          StrUtils.emailFormat(toEmail),
          e);
      throw new RuntimeException("Failed to send password reset email", e);
    }
  }

  private String buildHtmlContent(String displayName, String verificationLink) {
    return "<p>Hello "
        + displayName
        + ",</p>"
        + "<p>Please verify your email address by clicking the link below:</p>"
        + "<p><a href=\""
        + verificationLink
        + "\">Verify email</a></p>";
  }

  private String buildResetHtmlContent(String displayName, String resetLink) {
    return "<p>Hello "
        + displayName
        + ",</p>"
        + "<p>Reset your password by clicking the link below:</p>"
        + "<p><a href=\""
        + resetLink
        + "\">Reset password</a></p>";
  }
}
