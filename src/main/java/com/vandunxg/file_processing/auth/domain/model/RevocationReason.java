package com.vandunxg.file_processing.auth.domain.model;

/**
 * Why a refresh session stopped being usable.
 *
 * <p>The point of recording this is that an audit reader can answer "why did these sessions end?"
 * without correlating timestamps, so the values stay as specific as the event that caused them.
 * Names are persisted, so existing ones are never renamed — only added to.
 */
public enum RevocationReason {

  /** The user logged out of this one session. */
  LOGOUT,

  /** The user ended every session, e.g. from the session list. */
  USER_TRIGGERED,

  /** The user revoked one other session of theirs. */
  USER_REVOKED,

  /** The user changed their own password. */
  PASSWORD_CHANGED,

  /** The password was reset through the emailed link, or by an admin. */
  PASSWORD_RESET,

  /** The user's set of roles changed. */
  ROLE_CHANGED,

  /** A role the user holds had its permissions or inheritance changed. */
  PERMISSION_CHANGED,

  /** An admin disabled the account. */
  DISABLED,

  /** An already-consumed refresh token was presented again. */
  TOKEN_REUSE,

  /** An admin ended the session by an action other than the ones above. */
  ADMIN_REVOKED,

  /**
   * Superseded by the specific admin reasons above. Retained because rows already carry it; do not
   * write it in new code.
   *
   * @deprecated use {@link #DISABLED}, {@link #ROLE_CHANGED}, {@link #PERMISSION_CHANGED}, {@link
   *     #PASSWORD_RESET} or {@link #ADMIN_REVOKED}
   */
  @Deprecated
  ADMIN,

  /** The session passed its absolute expiry. */
  EXPIRED
}
