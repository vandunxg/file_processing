package com.vandunxg.file_processing.fileimport.configuration;

import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaRepositories(
    basePackages = "com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity")
public class FileImportPersistenceConfiguration {}
