package com.vandunxg.file_processing.fileimport.infrastructure.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(
    basePackages = "com.vandunxg.file_processing.fileimport.infrastructure.persistence")
public class FileImportPersistenceConfiguration {}
