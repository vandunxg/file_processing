package com.vandunxg.file_processing.auth.infrastructure.scheduling;

import com.vandunxg.file_processing.auth.application.service.AuthCleanupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CLEANUP")
public class AuthCleanupScheduler {

  private final AuthCleanupService authCleanupService;

  @Scheduled(fixedDelayString = "${app.auth.cleanup.cadence}")
  public void cleanExpired() {
    AuthCleanupService.CleanupResult result = authCleanupService.cleanExpired();
    if (!result.isEmpty()) {
      log.info(
          "[cleanup] expired password reset tokens={} refresh families={}",
          result.passwordResetTokens(),
          result.refreshFamilies());
    }
  }
}
