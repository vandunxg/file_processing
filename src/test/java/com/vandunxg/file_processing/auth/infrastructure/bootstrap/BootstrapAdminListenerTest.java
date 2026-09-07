package com.vandunxg.file_processing.auth.infrastructure.bootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vandunxg.file_processing.auth.application.service.AdminBootstrapService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class BootstrapAdminListenerTest {

  @Test
  void applicationReadyTriggersBootstrapService() {
    AdminBootstrapService adminBootstrapService = mock(AdminBootstrapService.class);

    new BootstrapAdminListener(adminBootstrapService)
        .onApplicationReady(mock(ApplicationReadyEvent.class));

    verify(adminBootstrapService).bootstrap();
  }
}
