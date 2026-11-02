package com.vandunxg.file_processing.fileimport.application.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

/** Shared, in-process shutdown signal for schedulers and active import runs. */
@Component
public class ProcessingWorkerControl {

  private final AtomicBoolean stopping = new AtomicBoolean();

  public boolean isStopping() {
    return stopping.get();
  }

  public void requestStop() {
    stopping.set(true);
  }
}
