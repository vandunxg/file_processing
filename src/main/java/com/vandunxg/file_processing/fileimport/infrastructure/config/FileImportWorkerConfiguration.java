package com.vandunxg.file_processing.fileimport.infrastructure.config;

import java.util.concurrent.Executor;

import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** The thread pool that actually runs imports, kept separate from Spring's task scheduler. */
@Configuration
public class FileImportWorkerConfiguration {

  @Bean("fileImportWorkerExecutor")
  public Executor fileImportWorkerExecutor(FileImportProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.workerThreads());
    executor.setMaxPoolSize(properties.workerThreads());
    // The scheduler only submits when a thread is free, so a queue would only ever hide a bug.
    executor.setQueueCapacity(0);
    executor.setThreadNamePrefix("file-import-worker-");
    // Runs observe the shared stop signal at a safe point. Give an in-flight transaction time to
    // commit before Spring closes the executor; anything still running is recovered as WORKER_LOST.
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(30);
    executor.initialize();
    return executor;
  }
}
