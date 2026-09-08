package com.vandunxg.file_processing.fileimport.api;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.capability.TokenIssuer;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.InMemoryFileStorage;
import com.vandunxg.file_processing.testsupport.InMemoryStorageConfiguration;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The HTTP contract of the import API: status codes, what a response exposes, and who may see whose
 * work.
 *
 * <p>Reaching another owner's job answers {@code 404} rather than {@code 403} on purpose -- {@code
 * 403} would confirm that the id exists and turn the endpoint into a way to enumerate other owners'
 * jobs.
 */
@PostgresIntegrationTest
@AutoConfigureMockMvc
@Import(InMemoryStorageConfiguration.class)
class FileImportControllerIT extends AuthIntegrationTestBase {

  private static final String CSV =
      "external_id,full_name,email,phone,date_of_birth,address\n"
          + "CUS_01,Nguyen Van A,a@example.com,0912345678,2000-01-02,1 Main St\n";
  private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

  /**
   * Authorization comes from the caller's role in the database, not from the token, so a test picks
   * a role rather than a list of permissions. {@code OPERATOR} is seeded with the self-service
   * permissions this API needs; {@code ADMIN} holds {@code all:manage}.
   */
  private static final String OWNER_ROLE = "OPERATOR";

  private static final String CROSS_OWNER_ROLE = "ADMIN";

  @Autowired private MockMvc mockMvc;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private TokenIssuer tokenIssuer;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;
  @Autowired private InMemoryFileStorage storage;

  @BeforeEach
  void reset() {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM customers");
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
    storage.clear();
  }

  @Test
  void uploadIsAcceptedAndDescribesAJobToWatchRatherThanAFinishedImport() throws Exception {
    Caller owner = caller(OWNER_ROLE);

    mockMvc
        .perform(
            multipart("/api/v1/file-import")
                .file(
                    new MockMultipartFile(
                        "file", "customers.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.data.jobStatus").value("QUEUED"))
        .andExpect(jsonPath("$.data.jobId").isNotEmpty())
        .andExpect(jsonPath("$.data.fileId").isNotEmpty())
        .andExpect(jsonPath("$.data.originalFilename").value("customers.csv"))
        // Storage layout is not a client's business.
        .andExpect(jsonPath("$.data.storageKey").doesNotExist())
        .andExpect(jsonPath("$.data.bucket").doesNotExist());
  }

  @Test
  void uploadRequiresThePermissionToCreateAFile() throws Exception {
    Caller reader = callerWithoutFileImportPermissions();

    mockMvc
        .perform(
            multipart("/api/v1/file-import")
                .file(
                    new MockMultipartFile(
                        "file", "customers.csv", "text/csv", CSV.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + reader.token()))
        .andExpect(status().isForbidden());
  }

  @Test
  void anOwnerReadsTheirOwnJobAndItsAttemptHistory() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobId").value(jobId.toString()))
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.originalFilename").value("mine.csv"))
        .andExpect(jsonPath("$.data.attempts").isArray())
        .andExpect(jsonPath("$.data.errorReportAvailable").value(false))
        .andExpect(jsonPath("$.data.ownerId").value(owner.userId().toString()))
        .andExpect(jsonPath("$.data.storageKey").doesNotExist())
        .andExpect(jsonPath("$.data.errorCode").doesNotExist());
  }

  @Test
  void theDetailViewSaysWhichActionsAreActuallyAvailable() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    // Queued: it can be cancelled, and nothing else -- there is no failed run to retry and no
    // report to download.
    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(jsonPath("$.data.availableActions", hasItem("CANCEL")))
        .andExpect(jsonPath("$.data.availableActions", not(hasItem("RETRY"))))
        .andExpect(jsonPath("$.data.availableActions", not(hasItem("DOWNLOAD_ERROR_REPORT"))));

    mockMvc
        .perform(
            post("/api/v1/file-import/jobs/{jobId}/cancel", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isAccepted());

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(jsonPath("$.data.availableActions", hasItem("RETRY")))
        .andExpect(jsonPath("$.data.availableActions", not(hasItem("CANCEL"))));
  }

  @Test
  void anotherOwnersJobIsNotFoundRatherThanForbidden() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID somebodyElsesJob = queue(UUID.randomUUID(), "theirs.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", somebodyElsesJob)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isNotFound());
  }

  @Test
  void aCallerAllowedToActOnAnyOwnerReadsAnyJob() throws Exception {
    Caller admin = caller(CROSS_OWNER_ROLE);
    UUID somebodyElsesJob = queue(UUID.randomUUID(), "theirs.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", somebodyElsesJob)
                .header("Authorization", "Bearer " + admin.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobId").value(somebodyElsesJob.toString()));
  }

  @Test
  void progressCarriesCountersAndNothingAboutTheFile() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}/progress", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("QUEUED"))
        .andExpect(jsonPath("$.data.processedRows").value(0))
        .andExpect(jsonPath("$.data.totalRows").doesNotExist())
        .andExpect(jsonPath("$.data.originalFilename").doesNotExist())
        .andExpect(jsonPath("$.data.attempts").doesNotExist());
  }

  @Test
  void theListShowsOnlyTheCallersOwnJobs() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID mine = queue(owner.userId(), "mine.csv", NOW);
    queue(UUID.randomUUID(), "theirs.csv", NOW);

    mockMvc
        .perform(get("/api/v1/file-import/jobs").header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].jobId").value(mine.toString()))
        .andExpect(jsonPath("$.data[0].originalFilename").value("mine.csv"))
        .andExpect(jsonPath("$.data[0].attempts").doesNotExist());
  }

  @Test
  void anOwnerFilterCannotWidenTheScopeOfACallerLimitedToTheirOwnJobs() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID otherOwner = UUID.randomUUID();
    UUID mine = queue(owner.userId(), "mine.csv", NOW);
    queue(otherOwner, "theirs.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("ownerId", otherOwner.toString())
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].jobId").value(mine.toString()));
  }

  @Test
  void aCallerAllowedToActOnAnyOwnerCanListOneOwnersJobs() throws Exception {
    Caller admin = caller(CROSS_OWNER_ROLE);
    UUID wantedOwner = UUID.randomUUID();
    UUID wanted = queue(wantedOwner, "wanted.csv", NOW);
    queue(UUID.randomUUID(), "other.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("ownerId", wantedOwner.toString())
                .header("Authorization", "Bearer " + admin.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].jobId").value(wanted.toString()));
  }

  @Test
  void theListFiltersByStatusFilenameAndCreationTime() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID wanted = queue(owner.userId(), "Q3-Customers.csv", NOW);
    queue(owner.userId(), "suppliers.csv", NOW.minusSeconds(86_400));

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("status", "QUEUED")
                .queryParam("keyword", "customers")
                .queryParam("createdFrom", NOW.minusSeconds(60).toString())
                .queryParam("createdTo", NOW.plusSeconds(60).toString())
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].jobId").value(wanted.toString()));
  }

  @Test
  void theListDefaultsToTwentyRowsOnTheFirstPage() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(get("/api/v1/file-import/jobs").header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.pageIndex").value(1))
        .andExpect(jsonPath("$.page.pageSize").value(20));
  }

  @Test
  void theListRefusesAPageLargerThanTheMaximum() throws Exception {
    Caller owner = caller(OWNER_ROLE);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("pageSize", "101")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theListRefusesAPageIndexBelowTheFirstPage() throws Exception {
    Caller owner = caller(OWNER_ROLE);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("pageIndex", "0")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theListRefusesToSortBySomethingThatIsNotAJobColumn() throws Exception {
    Caller owner = caller(OWNER_ROLE);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("sortBy", "attempts.asc")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void theListRequiresTheJobReadPermission() throws Exception {
    Caller stranger = callerWithoutFileImportPermissions();

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs").header("Authorization", "Bearer " + stranger.token()))
        .andExpect(status().isForbidden());
  }

  @Test
  void cancellingAQueuedJobIsAcceptedAndRetiresItWithoutAWorker() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(
            post("/api/v1/file-import/jobs/{jobId}/cancel", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isAccepted());

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(jsonPath("$.data.status").value(JobStatus.CANCELLED.name()));
  }

  @Test
  void retryIsRefusedWhileTheJobHasNotFinished() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(
            post("/api/v1/file-import/jobs/{jobId}/retry", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isConflict());
  }

  @Test
  void theErrorReportIsUnavailableUntilARunFinishesWithRejectedRows() throws Exception {
    Caller owner = caller(OWNER_ROLE);
    UUID jobId = queue(owner.userId(), "mine.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}/error-report", jobId)
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isConflict());
  }

  @Test
  void aJobManagerReachesAnotherOwnersJobWithoutHoldingEveryPermission() throws Exception {
    // job:manage is this module's own cross-owner permission, so an operations role can be granted
    // it without also being handed all:manage over the rest of the system.
    Caller manager = caller(roleGranting(ResourceCode.JOB, Action.MANAGE));
    UUID somebodyElsesJob = queue(UUID.randomUUID(), "theirs.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs/{jobId}", somebodyElsesJob)
                .header("Authorization", "Bearer " + manager.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.jobId").value(somebodyElsesJob.toString()));
  }

  @Test
  void aJobManagerCanCancelAndListAcrossOwners() throws Exception {
    Caller manager = caller(roleGranting(ResourceCode.JOB, Action.MANAGE));
    UUID otherOwner = UUID.randomUUID();
    UUID somebodyElsesJob = queue(otherOwner, "theirs.csv", NOW);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("ownerId", otherOwner.toString())
                .header("Authorization", "Bearer " + manager.token()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1));

    mockMvc
        .perform(
            post("/api/v1/file-import/jobs/{jobId}/cancel", somebodyElsesJob)
                .header("Authorization", "Bearer " + manager.token()))
        .andExpect(status().isAccepted());
  }

  @Test
  void theListRefusesATimeFilterThatIsNotAnInstant() throws Exception {
    Caller owner = caller(OWNER_ROLE);

    mockMvc
        .perform(
            get("/api/v1/file-import/jobs")
                .queryParam("createdFrom", "2026-09-08")
                .header("Authorization", "Bearer " + owner.token()))
        .andExpect(status().isBadRequest());
  }

  /** Stores a file and queues its job for {@code ownerId}, backdated to {@code createdAt}. */
  private UUID queue(UUID ownerId, String filename, Instant createdAt) {
    UUID fileId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                """
                INSERT INTO file_import (
                  id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
                  detected_content_type, retention_deadline, bucket, storage_provider,
                  created_at, last_modified_at
                ) VALUES (?, ?, ?, ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2', ?, ?)
                """,
                fileId,
                ownerId,
                filename,
                "imports/" + fileId + ".csv",
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(createdAt.plusSeconds(86_400)),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)));
    ProcessingJob job = jobRepository.save(ProcessingJob.queue(fileId, ownerId, createdAt));
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                "UPDATE processing_job SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                job.getId()));
    return job.getId();
  }

  /** A caller holding a role that grants nothing this API asks for. */
  private Caller callerWithoutFileImportPermissions() {
    return caller(roleGranting(ResourceCode.USER, Action.SELF_READ));
  }

  /** A role granting exactly one permission, so a test can isolate what that permission buys. */
  private Role roleGranting(ResourceCode resourceCode, Action action) {
    Instant now = Instant.now();
    Role role =
        roleRepository.save(
            Role.create(
                resourceCode.name() + "_" + action.name() + "_" + System.nanoTime(),
                "Single permission",
                null,
                now));
    roleRepository.replacePermissions(
        role.getId(), List.of(RolePermission.grant(role.getId(), resourceCode, action)), now);
    return role;
  }

  private Caller caller(String roleCode) {
    return caller(roleRepository.findByCode(roleCode).orElseThrow());
  }

  private Caller caller(Role role) {
    Instant now = Instant.now();
    User saved =
        userRepository.save(
            User.adminCreate(
                "import-" + System.nanoTime(),
                "import-" + UUID.randomUUID() + "@example.com",
                "Import Caller",
                "{bcrypt}$2a$stubhash",
                Set.of(role),
                true,
                now));
    userRepository.replaceRoles(saved.getId(), Set.of(role.getId()), now);
    Session session =
        Session.issue(
            UUID.randomUUID(),
            saved.getId(),
            saved.getCredentialVersion(),
            null,
            "JUnit",
            null,
            now,
            Duration.ofHours(1));
    sessionRepository.save(
        session,
        HashUtils.sha256(("refresh-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
    String token =
        tokenIssuer
            .issue(
                saved.getId(),
                session.getId(),
                saved.getCredentialVersion(),
                List.of(role.getCode()),
                List.of(),
                now)
            .token();
    return new Caller(saved.getId(), token);
  }

  private record Caller(UUID userId, String token) {}
}
