package com.vandunxg.file_processing.auth.application.query;

import com.vandunxg.common.persistence.query.PagingQuery;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
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
public class RoleSearchQuery extends PagingQuery {
  private ActiveStatus status;
}
