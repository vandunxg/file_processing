package com.vandunxg.file_processing.auth.application.capability;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.Role;

/**
 * Paginated role read model. See {@link UserSearchRepository} for why this is not on the aggregate.
 */
public interface RoleSearchRepository {

  Long count(RoleSearchQuery query);

  List<Role> search(RoleSearchQuery query);
}
