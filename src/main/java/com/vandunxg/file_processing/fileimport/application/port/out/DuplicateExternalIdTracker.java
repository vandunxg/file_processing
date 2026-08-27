package com.vandunxg.file_processing.fileimport.application.port.out;

public interface DuplicateExternalIdTracker {

  Run open();

  interface Run extends AutoCloseable {

    boolean firstOccurrence(String externalId);

    @Override
    void close();
  }
}
