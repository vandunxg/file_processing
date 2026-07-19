package com.vandunxg.file_processing.auth.domain.model;

public enum RevocationReason {
  LOGOUT,
  USER_TRIGGERED,
  PASSWORD_CHANGED,
  TOKEN_REUSE,
  ADMIN,
  EXPIRED
}
