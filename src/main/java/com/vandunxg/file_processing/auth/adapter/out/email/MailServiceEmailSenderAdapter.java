package com.vandunxg.file_processing.auth.adapter.out.email;

import org.springframework.stereotype.Component;

import com.vandunxg.common.email.MailService;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;

import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL")
public class MailServiceEmailSenderAdapter implements EmailSenderPort {

  private static final String SUBJECT = "Verify your email address";

  private final MailService mailService;

  @Override
  public void sendVerificationEmail(String toEmail, String displayName, String verificationLink) {
    String content = buildHtmlContent(displayName, verificationLink);
    try {
      mailService.sendHtmlMail(toEmail, SUBJECT, content);
    } catch (MessagingException e) {
      // Never log verificationLink here: it carries the raw opaque token.
      log.error(
          "[sendVerificationEmail] failed to send verification email toEmail={}",
          StrUtils.emailFormat(toEmail),
          e);
      throw new RuntimeException("Failed to send verification email", e);
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
}
