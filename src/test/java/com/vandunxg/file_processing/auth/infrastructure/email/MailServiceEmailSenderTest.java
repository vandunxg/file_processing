package com.vandunxg.file_processing.auth.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.vandunxg.common.email.MailService;
import jakarta.mail.MessagingException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailServiceEmailSenderTest {

  @Mock private MailService mailService;

  @Test
  void sendVerificationEmail_escapesDisplayNameAndKeepsVerificationLink()
      throws MessagingException {
    MailServiceEmailSender sender = new MailServiceEmailSender(mailService);
    String verificationLink = "https://app.example.com/verify?token=opaque-token&source=email";

    sender.sendVerificationEmail("user@example.com", "<img src=\"x\">", verificationLink);

    ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
    verify(mailService)
        .sendHtmlMail(eq("user@example.com"), eq("Verify your email address"), content.capture());
    assertThat(content.getValue())
        .contains("&lt;img src=&quot;x&quot;&gt;")
        .doesNotContain("<img")
        .contains("href=\"" + verificationLink + "\"");
  }

  @Test
  void sendPasswordResetEmail_escapesDisplayNameAndKeepsResetLink() throws MessagingException {
    MailServiceEmailSender sender = new MailServiceEmailSender(mailService);
    String resetLink = "https://app.example.com/reset?token=opaque-token&source=email";

    sender.sendPasswordResetEmail("user@example.com", "<img src=\"x\">", resetLink);

    ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
    verify(mailService)
        .sendHtmlMail(eq("user@example.com"), eq("Reset your password"), content.capture());
    assertThat(content.getValue())
        .contains("&lt;img src=&quot;x&quot;&gt;")
        .doesNotContain("<img")
        .contains("href=\"" + resetLink + "\"");
  }
}
