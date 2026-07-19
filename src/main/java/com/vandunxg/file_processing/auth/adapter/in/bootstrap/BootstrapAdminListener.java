package com.vandunxg.file_processing.auth.adapter.in.bootstrap;

import com.vandunxg.file_processing.auth.application.port.in.BootstrapAdminUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BootstrapAdminListener {

  private final BootstrapAdminUseCase bootstrapAdminUseCase;

  @EventListener(ApplicationReadyEvent.class)
  public void onApplicationReady(ApplicationReadyEvent ignored) {
    bootstrapAdminUseCase.bootstrap();
  }
}
