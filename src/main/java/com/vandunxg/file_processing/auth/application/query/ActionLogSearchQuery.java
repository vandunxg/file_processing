package com.vandunxg.file_processing.auth.application.query;

import java.time.Instant;

import com.vandunxg.common.persistence.query.PagingQuery;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@SuperBuilder
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class ActionLogSearchQuery extends PagingQuery {
  private String username;
  private String apiDoc;
  private String errorMessage;
  private String requestMethod;
  private Instant startTimeFrom;
  private Instant startTimeTo;
}
