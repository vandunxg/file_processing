package com.vandunxg.file_processing.auth.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class AuthRedisConfiguration {

  @Bean
  RedisScript<Long> slidingWindowRateLimiterScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/sliding-window-rate-limiter.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> emailVerificationIssueScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/email-verification-issue.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> emailVerificationInvalidateScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/email-verification-invalidate.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> refreshTokenRotateScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/refresh-token-rotate.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> sessionRevokeScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/session-revoke.lua"));
    script.setResultType(Long.class);
    return script;
  }
}
