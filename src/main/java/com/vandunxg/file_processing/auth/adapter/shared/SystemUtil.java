package com.vandunxg.file_processing.auth.adapter.shared;

import lombok.extern.slf4j.Slf4j;

@Slf4j(topic = "SYSTEM-UTIL")
public class SystemUtil {

  private SystemUtil() {}

  public static void gc() {
    for (int i = 0; i < 3; i++) {
      try {
        Thread.sleep(5000);
        System.gc();
      } catch (InterruptedException e) {
        log.warn("Trigger gc fail");
      }
    }
  }
}
