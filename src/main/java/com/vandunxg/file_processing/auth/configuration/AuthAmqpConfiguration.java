package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import com.vandunxg.common.amqp.support.QueueOptions;
import lombok.RequiredArgsConstructor;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;

@Configuration
@RequiredArgsConstructor
public class AuthAmqpConfiguration {

  private final AuthProperties authProperties;

  @Bean
  TopicExchange authEventsExchange() {
    return new TopicExchange(authProperties.amqp().exchange(), true, false);
  }

  @Bean
  TopicExchange authEventsDeadLetterExchange() {
    return new TopicExchange(authProperties.amqp().exchange() + ".dlx", true, false);
  }

  @Bean
  Queue auditLogQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().auditLog())
        .withArgument(QueueOptions.DEAD_LETTER_EXCHANGE, authProperties.amqp().exchange() + ".dlx")
        .withArgument(
            QueueOptions.DEAD_LETTER_ROUTING_KEY, authProperties.amqp().routingKey().auditLog())
        .build();
  }

  @Bean
  Queue auditLogDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().auditLog() + ".dlq").build();
  }

  @Bean
  Queue verificationEmailQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().verificationEmail())
        .withArgument(QueueOptions.DEAD_LETTER_EXCHANGE, authProperties.amqp().exchange() + ".dlx")
        .withArgument(
            QueueOptions.DEAD_LETTER_ROUTING_KEY,
            authProperties.amqp().routingKey().verificationEmail())
        .build();
  }

  @Bean
  Queue verificationEmailDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().verificationEmail() + ".dlq").build();
  }

  @Bean
  Binding auditLogBinding() {
    return BindingBuilder.bind(auditLogQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().auditLog());
  }

  @Bean
  Binding auditLogDeadLetterBinding() {
    return BindingBuilder.bind(auditLogDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().auditLog());
  }

  @Bean
  Binding verificationEmailBinding() {
    return BindingBuilder.bind(verificationEmailQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().verificationEmail());
  }

  @Bean
  Binding verificationEmailDeadLetterBinding() {
    return BindingBuilder.bind(verificationEmailDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().verificationEmail());
  }

  @Bean
  Queue sessionPersistQueue() {
    return sessionQueue(
        authProperties.amqp().queue().sessionPersist(),
        authProperties.amqp().routingKey().sessionPersist());
  }

  @Bean
  Queue sessionPersistDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().sessionPersist() + ".dlq").build();
  }

  @Bean
  Queue sessionUpdateQueue() {
    return sessionQueue(
        authProperties.amqp().queue().sessionUpdate(),
        authProperties.amqp().routingKey().sessionUpdate());
  }

  @Bean
  Queue sessionUpdateDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().sessionUpdate() + ".dlq").build();
  }

  @Bean
  Queue sessionRevokeQueue() {
    return sessionQueue(
        authProperties.amqp().queue().sessionRevoke(),
        authProperties.amqp().routingKey().sessionRevoke());
  }

  @Bean
  Queue sessionRevokeDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().sessionRevoke() + ".dlq").build();
  }

  @Bean
  Binding sessionPersistBinding() {
    return BindingBuilder.bind(sessionPersistQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().sessionPersist());
  }

  @Bean
  Binding sessionPersistDeadLetterBinding() {
    return BindingBuilder.bind(sessionPersistDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().sessionPersist());
  }

  @Bean
  Binding sessionUpdateBinding() {
    return BindingBuilder.bind(sessionUpdateQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().sessionUpdate());
  }

  @Bean
  Binding sessionUpdateDeadLetterBinding() {
    return BindingBuilder.bind(sessionUpdateDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().sessionUpdate());
  }

  @Bean
  Binding sessionRevokeBinding() {
    return BindingBuilder.bind(sessionRevokeQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().sessionRevoke());
  }

  @Bean
  Binding sessionRevokeDeadLetterBinding() {
    return BindingBuilder.bind(sessionRevokeDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().sessionRevoke());
  }

  private Queue sessionQueue(String queueName, String routingKey) {
    return QueueBuilder.durable(queueName)
        .withArgument(QueueOptions.DEAD_LETTER_EXCHANGE, authProperties.amqp().exchange() + ".dlx")
        .withArgument(QueueOptions.DEAD_LETTER_ROUTING_KEY, routingKey)
        .build();
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter messageConverter) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(messageConverter);

    // Local retry (3 attempts, 1s -> 10s exponential backoff) runs in-process against the same
    // delivered message, no broker requeue involved. Only once retries are exhausted does
    // RejectAndDontRequeueRecoverer nack without requeue, which is what routes the message to its
    // queue's configured DLX/DLQ (see auditLogQueue()/verificationEmailQueue() above) instead of
    // losing it or retrying forever in a tight loop.
    RetryPolicy retryPolicy =
        RetryPolicy.builder()
            .maxRetries(3)
            .delay(Duration.ofSeconds(1))
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(10))
            .build();
    Advice retryAdvice =
        RetryInterceptorBuilder.stateless()
            .retryPolicy(retryPolicy)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build();
    factory.setContainerCustomizer(container -> container.setAdviceChain(retryAdvice));

    return factory;
  }
}
