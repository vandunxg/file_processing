package com.vandunxg.file_processing.auth.application;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Defers a side effect until the current transaction commits, so cache invalidation, audit
 * publication and email delivery never observe uncommitted state.
 *
 * <p>Outside an active transaction the action is dropped rather than run inline: every caller here
 * is a post-commit effect of a write use case, and running it without a transaction would publish
 * state that was never persisted.
 *
 * <p>Failures are contained here rather than left to each call site. Spring invokes after-commit
 * callbacks in registration order and does not catch what they throw, so one failing callback would
 * otherwise skip every callback registered after it — losing the audit event of a write that
 * already committed — and surface as a 500 for a request that actually succeeded.
 */
@Slf4j(topic = "AUTH-AFTER-COMMIT")
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
            try {
              action.run();
            } catch (Exception exception) {
              log.warn("[afterCommit] post-commit action failed and was skipped", exception);
            }
          }
        });
  }
}
