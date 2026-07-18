package com.vandunxg.file_processing.configuration;

import com.vandunxg.common.email.MailService;
import com.vandunxg.common.email.config.MailSenderFactory;
import com.vandunxg.common.email.impl.MailServiceImpl;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

@Configuration(proxyBeanMethods = false)
public class EmailConfiguration {

  @Bean
  @ConditionalOnMissingBean
  MailSenderFactory mailSenderFactory() {
    return new MailSenderFactory();
  }

  @Bean
  @ConditionalOnMissingBean(MailService.class)
  MailService mailService(
    JavaMailSender javaMailSender,
    MailSenderFactory mailSenderFactory
  ) {
    return new MailServiceImpl(
      javaMailSender,
      mailSenderFactory
    );
  }
}
