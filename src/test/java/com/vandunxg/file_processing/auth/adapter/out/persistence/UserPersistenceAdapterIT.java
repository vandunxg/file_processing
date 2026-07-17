package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Proves that {@link UserRepositoryPort#save(User)} uses {@code saveAndFlush} under the hood, so
 * the unique-constraint race on {@code normalized_username} surfaces synchronously to the caller
 * (as {@link DataIntegrityViolationException}) instead of at a later transaction commit. This is
 * what {@code RegisterService} (Task 4) depends on to turn a race into a clean 409 response.
 */
@PostgresIntegrationTest
class UserPersistenceAdapterIT extends PostgresTestContainerBase {

  @Autowired private UserRepositoryPort userRepositoryPort;
  @Autowired private RoleRepositoryPort roleRepositoryPort;

  @Test
  void save_exactlyOneWinsAndTheOtherThrowsDataIntegrityViolation_whenSameNormalizedUsernameRaces()
      throws Exception {
    Role operatorRole = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    Instant now = Instant.now();
    String sharedUsername = "race-user-" + System.nanoTime();

    User first =
        User.register(
            sharedUsername,
            "race-first-" + System.nanoTime() + "@example.com",
            "Race First",
            "{bcrypt}$2a$stubhash1",
            operatorRole,
            now);
    User second =
        User.register(
            sharedUsername,
            "race-second-" + System.nanoTime() + "@example.com",
            "Race Second",
            "{bcrypt}$2a$stubhash2",
            operatorRole,
            now);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> saveFirst = () -> attemptSave(first, ready, start);
      Callable<Boolean> saveSecond = () -> attemptSave(second, ready, start);

      Future<Boolean> firstResult = executor.submit(saveFirst);
      Future<Boolean> secondResult = executor.submit(saveSecond);

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      boolean firstSucceeded = firstResult.get(10, TimeUnit.SECONDS);
      boolean secondSucceeded = secondResult.get(10, TimeUnit.SECONDS);

      assertThat(firstSucceeded ^ secondSucceeded)
          .as("exactly one of the two concurrent saves should succeed")
          .isTrue();
      assertThat(userRepositoryPort.existsByNormalizedUsername(User.normalize(sharedUsername)))
          .isTrue();
    } finally {
      executor.shutdown();
    }
  }

  private boolean attemptSave(User user, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    try {
      userRepositoryPort.save(user);
      return true;
    } catch (DataIntegrityViolationException e) {
      return false;
    }
  }
}
