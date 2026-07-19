package com.vandunxg.file_processing.auth.adapter.out.persistence;

import com.vandunxg.file_processing.auth.application.port.out.BootstrapAdminLockPort;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresBootstrapAdminLockAdapter implements BootstrapAdminLockPort {

  private static final long BOOTSTRAP_ADMIN_LOCK_ID = 8_451_995_241_020L;

  private final EntityManager entityManager;

  @Override
  public void acquire() {
    entityManager
        .createNativeQuery("select pg_advisory_xact_lock(" + BOOTSTRAP_ADMIN_LOCK_ID + ")")
        .getSingleResult();
  }
}
