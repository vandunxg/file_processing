package com.vandunxg.file_processing.auth.application.result;

import java.util.List;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record ResourcePermissionResult(
    ResourceCode resourceCode, String resourceGroup, List<Action> actions) {}
