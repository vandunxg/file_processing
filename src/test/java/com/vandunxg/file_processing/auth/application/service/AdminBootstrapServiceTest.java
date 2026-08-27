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

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.BootstrapAdminLock;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
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

  @Mock private BootstrapAdminLock bootstrapAdminLock;
  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private PasswordHasher passwordHasher;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;

  private AdminBootstrapService adminBootstrapService;

  @BeforeEach
  void setUp() {
    adminBootstrapService =
        new AdminBootstrapService(
            bootstrapAdminLock,
            userRepository,
            roleRepository,
            passwordHasher,
            new AuditTrail(auditLogEventPublisher),
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
    when(userRepository.existsAny()).thenReturn(false);
    when(roleRepository.findByCode("ADMIN")).thenReturn(Optional.of(adminRole));
    when(passwordHasher.hash("BootstrapPass123")).thenReturn("{bcrypt}hashed");
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    adminBootstrapService.bootstrap();

    verify(bootstrapAdminLock).acquire();
    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(userRepository).save(userCaptor.capture());
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
    verify(userRepository).assignRole(userRoleCaptor.capture());
    assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(user.getId());
    assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(adminRole.getId());
    verifyNoInteractions(auditLogEventPublisher);

    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisher).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.ADMIN_BOOTSTRAPPED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(user.getId());
    assertThat(auditCaptor.getValue().getChangedBy()).isNull();
  }

  @Test
  void bootstrapSkipsWhenAUserAlreadyExists() {
    when(userRepository.existsAny()).thenReturn(true);

    adminBootstrapService.bootstrap();

    verify(bootstrapAdminLock).acquire();
    verifyNoInteractions(roleRepository, passwordHasher, auditLogEventPublisher);
    verify(userRepository, never()).save(any());
  }

  @Test
  void bootstrapFailsFastForAnInvalidConfiguredPassword() {
    adminBootstrapService =
        new AdminBootstrapService(
            bootstrapAdminLock,
            userRepository,
            roleRepository,
            passwordHasher,
            new AuditTrail(auditLogEventPublisher),
            properties("short"),
            Clock.fixed(NOW, ZoneOffset.UTC));
    when(userRepository.existsAny()).thenReturn(false);

    assertThatThrownBy(() -> adminBootstrapService.bootstrap())
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Invalid bootstrap admin configuration");

    verify(passwordHasher, never()).hash(anyString());
    verifyNoInteractions(roleRepository, auditLogEventPublisher);
    verify(userRepository, never()).save(any());
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
