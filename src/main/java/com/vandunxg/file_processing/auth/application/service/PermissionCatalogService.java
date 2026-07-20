package com.vandunxg.file_processing.auth.application.service;

import java.util.Arrays;
import java.util.List;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import org.springframework.stereotype.Service;

@Service
public class PermissionCatalogService {

  public List<ResourcePermission> permissions() {
    return Arrays.stream(ResourceCode.values())
        .map(
            resource ->
                new ResourcePermission(resource, resource.getGroup(), List.of(Action.values())))
        .toList();
  }

  public List<ResourceCode> resources() {
    return List.of(ResourceCode.values());
  }

  public record ResourcePermission(
      ResourceCode resourceCode, String resourceGroup, List<Action> actions) {}
}
