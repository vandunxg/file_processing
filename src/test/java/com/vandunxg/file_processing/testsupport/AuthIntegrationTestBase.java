package com.vandunxg.file_processing.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Extends {@link PostgresTestContainerBase} with Redis and RabbitMQ containers, for any
 * full-context test whose Spring context loads the auth module's Redis-backed adapters and/or
 * always-on {@code @RabbitListener} beans. Those are wired unconditionally once the auth
 * configuration is on the classpath, regardless of whether an individual test exercises them, so
 * every {@code @PostgresIntegrationTest} class needs a reachable Redis/RabbitMQ or its context load
 * will try to reach one at {@code localhost}.
 */
public abstract class AuthIntegrationTestBase extends PostgresTestContainerBase {

  protected static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  protected static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine")).withReuse(true);

  static {
    REDIS.start();
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void authInfraProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
  }
}
