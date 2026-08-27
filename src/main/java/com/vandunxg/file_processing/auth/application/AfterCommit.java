package com.vandunxg.file_processing.auth.application;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the current transaction commits, so cache invalidation, audit
 * publication and email delivery never observe uncommitted state.
 *
 * <p>Outside an active transaction the action is dropped rather than run inline: every caller here
 * is a post-commit effect of a write use case, and running it without a transaction would publish
 * state that was never persisted.
 */
public final class AfterCommit {

  private AfterCommit() {}

  public static void run(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
