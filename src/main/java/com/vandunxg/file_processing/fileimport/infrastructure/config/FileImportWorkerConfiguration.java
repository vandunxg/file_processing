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
    // Shutdown does not wait for a run in progress: a large import can take far longer than any
    // reasonable shutdown grace period. The abandoned job keeps its committed batches and the
    // recovery scan requeues it after restart, which is exactly the case recovery exists for.
    executor.setWaitForTasksToCompleteOnShutdown(false);
    executor.initialize();
    return executor;
  }
}
