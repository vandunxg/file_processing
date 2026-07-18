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
}
