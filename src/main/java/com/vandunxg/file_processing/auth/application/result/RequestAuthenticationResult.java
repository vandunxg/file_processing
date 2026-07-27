package com.vandunxg.file_processing.auth.application.result;

import java.util.List;
import java.util.UUID;

public record RequestAuthenticationResult(UUID userId, String username, List<String> permissions) {}
