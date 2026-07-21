package com.vandunxg.file_processing.auth.application.service;

import java.util.Arrays;
import java.util.List;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.application.port.in.PermissionCatalogUseCase;
import com.vandunxg.file_processing.auth.application.result.PermissionResourceResult;
import com.vandunxg.file_processing.auth.application.result.ResourcePermissionResult;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import org.springframework.stereotype.Service;

@Service
public class PermissionCatalogService implements PermissionCatalogUseCase {

  @Override
  public List<ResourcePermissionResult> permissions() {
    return Arrays.stream(ResourceCode.values())
        .map(
            resource ->
                new ResourcePermissionResult(
                    resource, resource.getGroup(), List.of(Action.values())))
        .toList();
  }

  @Override
  public List<PermissionResourceResult> resources() {
    return Arrays.stream(ResourceCode.values())
        .map(resource -> new PermissionResourceResult(resource, resource.getGroup()))
        .toList();
  }
}
