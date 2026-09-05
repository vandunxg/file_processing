package com.vandunxg.file_processing.auth.api.dto.response;

import java.util.List;

public record ResourcePermissionResponse(
    String resourceCode, String resourceGroup, List<String> actions) {}
