package com.vandunxg.file_processing.auth.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
@EnableJpaRepositories(
    basePackages = "com.vandunxg.file_processing.auth.adapter.out.persistence.entity")
public class AuthPersistenceConfiguration {}
