package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class UserAdminCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private PasswordHasher passwordHasher;
  @Mock private SessionRepository sessionRepository;
  @Mock private CredentialVersionCache credentialVersionCache;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;

  @Test
  void disableRejectsTheLastActiveAdminWhileHoldingTheAdminRoleLock() {
    UUID userId = UUID.randomUUID();
    Role admin =
        Role.builder()
            .id(UUID.randomUUID())
            .code("ADMIN")
            .name("Administrator")
            .isConst(true)
            .status(ActiveStatus.ACTIVE)
            .build();
    User user =
        User.builder()
            .id(userId)
            .status(UserStatus.ACTIVE)
            .roles(Set.of(admin))
            .credentialVersion(1)
            .build();
    when(roleRepository.lockAdminRole()).thenReturn(admin);
    when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    when(userRepository.countActiveAdmins()).thenReturn(1L);

    UserAdminCommandService service =
        new UserAdminCommandService(
            userRepository,
            roleRepository,
            passwordHasher,
            sessionRepository,
            credentialVersionCache,
            new AuditTrail(auditLogEventPublisher),
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.disable(UUID.randomUUID(), userId))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_LAST_ACTIVE_ADMIN);

    verify(roleRepository).lockAdminRole();
    verify(userRepository, never()).save(user);
    verify(sessionRepository, never()).revokeAllForUser(userId, null, NOW);
  }

  @Test
  void enableInvalidatesCredentialsAndLeavesNoPriorSessionsUsable() {
    UUID userId = UUID.randomUUID();
    User user = User.builder().id(userId).status(UserStatus.DISABLED).credentialVersion(1).build();
    when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    when(userRepository.save(user)).thenReturn(user);
    TransactionSynchronizationManager.initSynchronization();

    newService().enable(UUID.randomUUID(), userId);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepository)
        .revokeAllForUser(
            eq(userId),
            eq(com.vandunxg.file_processing.auth.domain.model.RevocationReason.ADMIN),
            eq(NOW));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(credentialVersionCache).invalidate(userId);
    TransactionSynchronizationManager.clearSynchronization();
  }

  private UserAdminCommandService newService() {
    return new UserAdminCommandService(
        userRepository,
        roleRepository,
        passwordHasher,
        sessionRepository,
        credentialVersionCache,
        new AuditTrail(auditLogEventPublisher),
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
}
