package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.capability.ActionLogSearchRepository;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActionLogQueryServiceTest {

  @Mock private ActionLogSearchRepository actionLogSearchRepository;

  @Test
  void searchReturnsActionLogsWithRequestedPageMetadata() {
    ActionLogSearchQuery query = ActionLogSearchQuery.builder().pageIndex(2).pageSize(10).build();
    ActionLog actionLog = actionLog();
    when(actionLogSearchRepository.count(query)).thenReturn(11L);
    when(actionLogSearchRepository.search(query)).thenReturn(List.of(actionLog));

    PageDTO<ActionLog> result = new ActionLogQueryService(actionLogSearchRepository).search(query);

    assertThat(result.getData()).containsExactly(actionLog);
    assertThat(result.getPage().getPageIndex()).isEqualTo(2);
    assertThat(result.getPage().getPageSize()).isEqualTo(10);
    assertThat(result.getPage().getTotal()).isEqualTo(11);
  }

  @Test
  void searchPreservesRequestedMetadataWhenNoActionLogMatches() {
    ActionLogSearchQuery query = ActionLogSearchQuery.builder().pageIndex(4).pageSize(15).build();
    when(actionLogSearchRepository.count(query)).thenReturn(0L);

    PageDTO<ActionLog> result = new ActionLogQueryService(actionLogSearchRepository).search(query);

    assertThat(result.getData()).isEmpty();
    assertThat(result.getPage().getPageIndex()).isEqualTo(4);
    assertThat(result.getPage().getPageSize()).isEqualTo(15);
    assertThat(result.getPage().getTotal()).isZero();
    verify(actionLogSearchRepository, never()).search(query);
  }

  private static ActionLog actionLog() {
    Instant startTime = Instant.parse("2026-07-27T10:15:30Z");
    return ActionLog.builder()
        .id(UUID.randomUUID())
        .startTime(startTime)
        .endTime(startTime.plusMillis(25))
        .duration(25L)
        .path("/api/v1/jobs")
        .requestMethod("GET")
        .statusCode(500)
        .build();
  }
}
