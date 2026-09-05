package com.vandunxg.file_processing.auth.infrastructure.bootstrap;

import com.vandunxg.file_processing.auth.application.service.AdminBootstrapService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BootstrapAdminListener {

  private final AdminBootstrapService adminBootstrapService;

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady(ApplicationReadyEvent ignored) {
    adminBootstrapService.bootstrap();
  }
}
