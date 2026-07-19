package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.BootstrapAdminLockPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class BootstrapAdminServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");

  @Mock private BootstrapAdminLockPort bootstrapAdminLockPort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private RoleRepositoryPort roleRepositoryPort;
  @Mock private UserRoleRepositoryPort userRoleRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private BootstrapAdminService bootstrapAdminService;

  @BeforeEach
  void setUp() {
    bootstrapAdminService =
        new BootstrapAdminService(
            bootstrapAdminLockPort,
            userRepositoryPort,
            roleRepositoryPort,
            userRoleRepositoryPort,
            passwordHasherPort,
            auditLogEventPublisherPort,
            properties("BootstrapPass123"),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void bootstrapCreatesVerifiedAdminAndPublishesAuditAfterCommit() {
    Role adminRole = adminRole();
    when(userRepositoryPort.existsAny()).thenReturn(false);
    when(roleRepositoryPort.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
    when(passwordHasherPort.hash("BootstrapPass123")).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    bootstrapAdminService.bootstrap();

    verify(bootstrapAdminLockPort).acquire();
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepositoryPort).save(userCaptor.capture());
    User user = userCaptor.getValue();
    assertThat(user.getUsername()).isEqualTo("admin");
    assertThat(user.getNormalizedUsername()).isEqualTo("admin");
    assertThat(user.getEmail()).isEqualTo("admin@example.com");
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
    assertThat(user.isMustChangePassword()).isTrue();
    assertThat(user.getCredentialVersion()).isEqualTo(1);
    assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}hashed");
    assertThat(user.getRoles()).containsExactly(adminRole);

    ArgumentCaptor<UserRole> userRoleCaptor = ArgumentCaptor.forClass(UserRole.class);
    verify(userRoleRepositoryPort).save(userRoleCaptor.capture());
    assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(user.getId());
    assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(adminRole.getId());
    verifyNoInteractions(auditLogEventPublisherPort);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.ADMIN_BOOTSTRAPPED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(user.getId());
    assertThat(auditCaptor.getValue().getChangedBy()).isNull();
  }

  @Test
  void bootstrapSkipsWhenAUserAlreadyExists() {
    when(userRepositoryPort.existsAny()).thenReturn(true);

    bootstrapAdminService.bootstrap();

    verify(bootstrapAdminLockPort).acquire();
    verifyNoInteractions(
        roleRepositoryPort, userRoleRepositoryPort, passwordHasherPort, auditLogEventPublisherPort);
    verify(userRepositoryPort, never()).save(any());
  }

  @Test
  void bootstrapFailsFastForAnInvalidConfiguredPassword() {
    bootstrapAdminService =
        new BootstrapAdminService(
            bootstrapAdminLockPort,
            userRepositoryPort,
            roleRepositoryPort,
            userRoleRepositoryPort,
            passwordHasherPort,
            auditLogEventPublisherPort,
            properties("short"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(userRepositoryPort.existsAny()).thenReturn(false);

    assertThatThrownBy(() -> bootstrapAdminService.bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid bootstrap admin configuration");

    verify(passwordHasherPort, never()).hash(anyString());
    verifyNoInteractions(roleRepositoryPort, userRoleRepositoryPort, auditLogEventPublisherPort);
    verify(userRepositoryPort, never()).save(any());
  }

  private static AuthProperties properties(String password) {
    return new AuthProperties(
        null,
        null,
        null,
        null,
        null,
        new AuthProperties.Bootstrap(
            new AuthProperties.Bootstrap.Admin(
                true, "admin", "admin@example.com", password, "System Administrator")),
        null,
        null,
        null,
        null);
  }

  private static Role adminRole() {
    return Role.builder()
        .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
        .code("ADMIN")
        .status(ActiveStatus.ACTIVE)
        .build();
  }
}
