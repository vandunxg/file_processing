package com.vandunxg.file_processing.auth.application.port.in;

import java.util.List;

import com.vandunxg.file_processing.auth.application.result.PermissionResourceResult;
import com.vandunxg.file_processing.auth.application.result.ResourcePermissionResult;

public interface PermissionCatalogUseCase {

  List<PermissionResourceResult> resources();

  List<ResourcePermissionResult> permissions();
}
