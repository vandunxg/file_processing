package com.vandunxg.file_processing.auth.application.port.out;

public interface RegisterThrottlePort {

  /**
   * Returns {@code true} when allowed (one unit consumed), {@code false} when the limit is
   * exceeded.
   */
  boolean tryConsume(String key, int maxPerHour);
}
