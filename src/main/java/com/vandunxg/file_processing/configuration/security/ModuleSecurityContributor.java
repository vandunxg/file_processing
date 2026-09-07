package com.vandunxg.file_processing.configuration.security;

import java.util.List;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point a business module implements to declare what it needs from the application-wide
 * security chain.
 *
 * <p>It exists so the composition root never imports a module's {@code infrastructure} package and
 * never hardcodes a module's routes: the module owns its public endpoints, its authentication
 * mechanism, and its filters, while the root only decides where in the chain module contributions
 * are applied. Order several contributors with {@code @Order}. Ordering affects {@link
 * #contribute(HttpSecurity)} only — {@link #publicPaths()} results are unioned.
 */
public interface ModuleSecurityContributor {

  /**
   * Request path patterns this module serves without authentication. The root both permits these
   * paths and excludes them from the security filter chain.
   */
  default List<String> publicPaths() {
    return List.of();
  }

  /** Installs this module's authentication mechanism and request filters. */
  default void contribute(HttpSecurity http) throws Exception {}
}
