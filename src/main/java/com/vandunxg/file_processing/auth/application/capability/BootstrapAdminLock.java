package com.vandunxg.file_processing.auth.application.capability;

/** Acquires the transaction-scoped PostgreSQL lock that serializes the first-user bootstrap. */
public interface BootstrapAdminLock {

  void acquire();
}
