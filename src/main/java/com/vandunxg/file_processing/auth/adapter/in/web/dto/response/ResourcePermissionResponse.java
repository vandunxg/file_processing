package com.vandunxg.file_processing.auth.adapter.in.web.dto.response;

import java.util.List;

public record ResourcePermissionResponse(
    String resourceCode, String resourceGroup, List<String> actions) {}
