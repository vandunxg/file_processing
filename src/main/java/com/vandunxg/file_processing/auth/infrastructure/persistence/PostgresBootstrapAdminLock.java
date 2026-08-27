package com.vandunxg.file_processing.auth.infrastructure.persistence;

import com.vandunxg.file_processing.auth.application.capability.BootstrapAdminLock;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresBootstrapAdminLock implements BootstrapAdminLock {

  private static final long BOOTSTRAP_ADMIN_LOCK_ID = 8_451_995_241_020L;

  private final EntityManager entityManager;

  @Override
  public void acquire() {
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(" + BOOTSTRAP_ADMIN_LOCK_ID + ")")
        .getSingleResult();
  }
}
