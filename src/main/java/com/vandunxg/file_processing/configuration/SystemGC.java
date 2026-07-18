package com.vandunxg.file_processing.configuration;

import com.vandunxg.file_processing.auth.adapter.shared.SystemUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j(topic = "SYSTEM-GC")
@Component
public class SystemGC {

  @Scheduled(cron = "${app.gc.cron-time}")
  public void runSystemGC() {
    log.info("Starting trigger gc");
    SystemUtil.gc();
    log.info("Finishing trigger gc !!!");
  }
}
