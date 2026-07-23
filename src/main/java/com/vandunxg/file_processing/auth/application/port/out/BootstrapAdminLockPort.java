package com.vandunxg.file_processing.auth.application.port.out;

/** Acquires the transaction-scoped PostgreSQL lock that serializes the first-user bootstrap. */
public interface BootstrapAdminLockPort {

  void acquire();
}
