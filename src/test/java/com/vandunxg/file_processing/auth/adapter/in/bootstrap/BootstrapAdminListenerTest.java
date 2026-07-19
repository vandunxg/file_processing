package com.vandunxg.file_processing.auth.adapter.in.bootstrap;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.vandunxg.file_processing.auth.application.port.in.BootstrapAdminUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;

class BootstrapAdminListenerTest {

  @Test
  void applicationReadyTriggersBootstrapUseCase() {
    BootstrapAdminUseCase bootstrapAdminUseCase = mock(BootstrapAdminUseCase.class);

    new BootstrapAdminListener(bootstrapAdminUseCase)
        .onApplicationReady(mock(ApplicationReadyEvent.class));

    verify(bootstrapAdminUseCase).bootstrap();
  }
}
