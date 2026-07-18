package com.vandunxg.file_processing.auth.configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class AuthRedisConfiguration {

  @Bean
  RedisScript<Long> slidingWindowRateLimiterScript() throws IOException {
    String script = new String(
        new ClassPathResource("scripts/sliding-window-rate-limiter.lua").getContentAsByteArray(),
        StandardCharsets.UTF_8);
    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    redisScript.setScriptText(script);
    redisScript.setResultType(Long.class);
    return redisScript;
  }
}
