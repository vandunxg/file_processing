package com.vandunxg.file_processing.fileimport.application.capability;

public interface DuplicateExternalIdTracker {

  Run open();

  interface Run extends AutoCloseable {

    boolean firstOccurrence(String externalId);

    /** Checks a first valid occurrence without reserving an ID from an otherwise invalid row. */
    boolean alreadySeen(String externalId);

    @Override
    void close();
  }
}
