package com.vandunxg.file_processing.auth.domain.event;

public record SendVerificationEmailEvent(
    String toEmail, String displayName, String verificationLink) {}
