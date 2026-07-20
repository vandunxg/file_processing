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

import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
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
class AdminUserServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T12:00:00Z");

  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private RoleRepositoryPort roleRepositoryPort;
  @Mock private UserRoleRepositoryPort userRoleRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

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
    when(roleRepositoryPort.lockAdminRole()).thenReturn(admin);
    when(userRepositoryPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    when(userRepositoryPort.countActiveAdmins()).thenReturn(1L);

    AdminUserService service =
        new AdminUserService(
            userRepositoryPort,
            roleRepositoryPort,
            userRoleRepositoryPort,
            passwordHasherPort,
            sessionRepositoryPort,
            credentialVersionCachePort,
            auditLogEventPublisherPort,
            Clock.fixed(NOW, ZoneOffset.UTC));

    assertThatThrownBy(() -> service.disable(UUID.randomUUID(), userId))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.LAST_ACTIVE_ADMIN);

    verify(roleRepositoryPort).lockAdminRole();
    verify(userRepositoryPort, never()).save(user);
    verify(sessionRepositoryPort, never()).revokeAllForUser(userId, null, NOW);
  }

  @Test
  void enableInvalidatesCredentialsAndLeavesNoPriorSessionsUsable() {
    UUID userId = UUID.randomUUID();
    User user = User.builder().id(userId).status(UserStatus.DISABLED).credentialVersion(1).build();
    when(userRepositoryPort.findByIdForUpdate(userId)).thenReturn(Optional.of(user));
    when(userRepositoryPort.save(user)).thenReturn(user);
    TransactionSynchronizationManager.initSynchronization();

    newService().enable(UUID.randomUUID(), userId);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepositoryPort)
        .revokeAllForUser(
            eq(userId),
            eq(com.vandunxg.file_processing.auth.domain.model.RevocationReason.ADMIN),
            eq(NOW));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(credentialVersionCachePort).invalidate(userId);
    TransactionSynchronizationManager.clearSynchronization();
  }

  private AdminUserService newService() {
    return new AdminUserService(
        userRepositoryPort,
        roleRepositoryPort,
        userRoleRepositoryPort,
        passwordHasherPort,
        sessionRepositoryPort,
        credentialVersionCachePort,
        auditLogEventPublisherPort,
        Clock.fixed(NOW, ZoneOffset.UTC));
  }
}
