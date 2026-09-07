package com.vandunxg.file_processing.auth.application.capability;

import java.time.Duration;

public interface AuthThrottle {

  /**
   * Returns {@code true} when allowed (one unit consumed), {@code false} when the limit is
   * exceeded. Each call must specify the caller-owned window because the limiter is shared across
   * flows with different budgets (register uses PT1H, login-per-user uses PT15M, etc.).
   */
  boolean tryConsume(String key, int maxPerWindow, Duration window);
}
