package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.ProcessingJobSearchRepository;
import com.vandunxg.file_processing.fileimport.application.mapper.ProcessingJobResultMapperImpl;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobSummaryResult;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The list's authorization rule: a caller who may only see their own jobs cannot widen the scope by
 * asking for somebody else's.
 */
class ProcessingJobListTest {

  private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

  private final ProcessingJobSearchRepository searchRepository =
      mock(ProcessingJobSearchRepository.class);
  private final ImportFileRepository importFileRepository = mock(ImportFileRepository.class);

  private ProcessingJobQueryService service;

  @BeforeEach
  void createService() {
    service =
        new ProcessingJobQueryService(
            mock(ProcessingJobRepository.class),
            importFileRepository,
            searchRepository,
            mock(ErrorReportStore.class),
            new ProcessingJobResultMapperImpl(),
            mock(FileImportAuditService.class),
            Clock.fixed(NOW, java.time.ZoneOffset.UTC));
  }

  @Test
  void replacesARequestedOwnerFilterWithTheRequesterWhenTheyMayOnlySeeTheirOwn() {
    UUID requester = UUID.randomUUID();
    when(searchRepository.count(any())).thenReturn(0L);

    service.list(query(UUID.randomUUID()), requester, false);

    assertThat(capturedQuery().getOwnerId()).isEqualTo(requester);
  }

  @Test
  void scopesToTheRequesterWhenTheyNamedNoOwnerAndMayOnlySeeTheirOwn() {
    UUID requester = UUID.randomUUID();
    when(searchRepository.count(any())).thenReturn(0L);

    service.list(query(null), requester, false);

    assertThat(capturedQuery().getOwnerId()).isEqualTo(requester);
  }

  @Test
  void keepsTheRequestedOwnerFilterForACallerAllowedToActOnAnyOwner() {
    UUID wanted = UUID.randomUUID();
    when(searchRepository.count(any())).thenReturn(0L);

    service.list(query(wanted), UUID.randomUUID(), true);

    assertThat(capturedQuery().getOwnerId()).isEqualTo(wanted);
  }

  @Test
  void listsEveryOwnerForACallerAllowedToActOnAnyOwnerThatNamedNone() {
    when(searchRepository.count(any())).thenReturn(0L);

    service.list(query(null), UUID.randomUUID(), true);

    assertThat(capturedQuery().getOwnerId()).isNull();
  }

  @Test
  void doesNotRunTheSearchWhenNothingMatches() {
    when(searchRepository.count(any())).thenReturn(0L);

    PageDTO<ProcessingJobSummaryResult> page = service.list(query(null), UUID.randomUUID(), true);

    assertThat(page.getData()).isEmpty();
    assertThat(page.getPage().getTotal()).isZero();
    verify(searchRepository, never()).search(any());
  }

  @Test
  void loadsTheFilesOfAWholePageInOneLookup() {
    UUID owner = UUID.randomUUID();
    ImportFile first = file(owner, "first.csv", 11);
    ImportFile second = file(owner, "second.csv", 22);
    ProcessingJob firstJob = ProcessingJob.queue(first.getId(), owner, NOW);
    ProcessingJob secondJob = ProcessingJob.queue(second.getId(), owner, NOW);
    when(searchRepository.count(any())).thenReturn(2L);
    when(searchRepository.search(any())).thenReturn(List.of(firstJob, secondJob));
    when(importFileRepository.findAllByIds(anyCollection())).thenReturn(List.of(first, second));

    PageDTO<ProcessingJobSummaryResult> page = service.list(query(owner), owner, false);

    assertThat(page.getData())
        .extracting(
            ProcessingJobSummaryResult::jobId,
            ProcessingJobSummaryResult::originalFilename,
            ProcessingJobSummaryResult::sizeBytes)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(firstJob.getId(), "first.csv", 11L),
            org.assertj.core.groups.Tuple.tuple(secondJob.getId(), "second.csv", 22L));
    verify(importFileRepository, times(1)).findAllByIds(anyCollection());
    verify(importFileRepository, never()).findById(any());
  }

  private ProcessingJobSearchQuery capturedQuery() {
    ArgumentCaptor<ProcessingJobSearchQuery> captor =
        ArgumentCaptor.forClass(ProcessingJobSearchQuery.class);
    verify(searchRepository).count(captor.capture());
    return captor.getValue();
  }

  private static ProcessingJobSearchQuery query(UUID ownerId) {
    return ProcessingJobSearchQuery.builder().pageIndex(1).pageSize(20).ownerId(ownerId).build();
  }

  private static ImportFile file(UUID ownerId, String filename, long sizeBytes) {
    return ImportFile.reconstitute(
        UUID.randomUUID(),
        ownerId,
        filename,
        "imports/" + filename,
        new FileChecksum("a".repeat(64)),
        sizeBytes,
        "text/csv",
        NOW.plusSeconds(3600),
        "file-processing",
        StorageProvider.R2,
        0L,
        NOW,
        NOW);
  }
}
