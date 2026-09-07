package com.vandunxg.file_processing.auth.application.capability;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.User;

/**
 * Paginated user read model. Kept out of {@code UserRepository} because the paging contract belongs
 * to the application layer, not to the aggregate consistency boundary.
 */
public interface UserSearchRepository {

  Long count(UserSearchQuery query);

  List<User> search(UserSearchQuery query);
}
