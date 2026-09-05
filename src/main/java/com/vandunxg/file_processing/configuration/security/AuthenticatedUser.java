package com.vandunxg.file_processing.configuration.security;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.NullMarked;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

@NullMarked
public record AuthenticatedUser(
    UUID userId,
    String username,
    UUID sessionId,
    Collection<? extends GrantedAuthority> authorities)
    implements UserDetails {

  public AuthenticatedUser {
    Objects.requireNonNull(userId, "userId");
    Objects.requireNonNull(username, "username");
    Objects.requireNonNull(sessionId, "sessionId");
    authorities = List.copyOf(authorities);
  }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() {
    return authorities;
  }

  @Override
  public String getPassword() {
    return "";
  }

  @Override
  public String getUsername() {
    return username;
  }
}
