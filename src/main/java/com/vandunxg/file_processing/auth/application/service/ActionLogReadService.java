package com.vandunxg.file_processing.auth.application.service;

import java.util.List;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogPort;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ActionLogReadService {

  private final ActionLogPort actionLogPort;

  @Transactional(readOnly = true)
  public PageDTO<ActionLog> search(ActionLogSearchQuery query) {
    if (query.getSortBy() == null || query.getSortBy().isBlank()) {
      query.setSortBy("startTime.desc");
    }
    long count = actionLogPort.count(query);
    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }
    return PageDTO.of(
        actionLogPort.search(query), query.getPageIndex(), query.getPageSize(), count);
  }
}
