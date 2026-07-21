package com.vandunxg.file_processing.auth.application.result;

import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record PermissionResourceResult(ResourceCode resourceCode, String resourceGroup) {}
