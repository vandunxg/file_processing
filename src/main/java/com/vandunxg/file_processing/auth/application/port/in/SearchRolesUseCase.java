package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.Role;

public interface SearchRolesUseCase {

  PageDTO<Role> search(RoleSearchQuery query);
}
