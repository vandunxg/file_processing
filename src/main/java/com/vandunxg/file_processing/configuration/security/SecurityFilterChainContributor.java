package com.vandunxg.file_processing.configuration.security;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point a business module implements to install its own filters into the application-wide
 * security chain.
 *
 * <p>It exists so the composition root never imports a module's {@code infrastructure} package: the
 * module owns its filters and their relative order, the root only decides where in the chain module
 * contributions are applied. Order several contributors with {@code @Order}.
 */
public interface SecurityFilterChainContributor {

  void contribute(HttpSecurity http) throws Exception;
}
