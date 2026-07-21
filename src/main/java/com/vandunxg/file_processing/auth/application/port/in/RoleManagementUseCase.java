package com.vandunxg.file_processing.auth.application.port.in;

import java.util.UUID;

import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.domain.model.Role;

public interface RoleManagementUseCase {

  Role detail(UUID roleId);

  Role create(CreateRoleCommand command);

  Role update(UpdateRoleCommand command);

  Role setInheritance(SetRoleInheritanceCommand command);

  Role activate(RoleActionCommand command);

  Role inactivate(RoleActionCommand command);

  void delete(RoleActionCommand command);
}
