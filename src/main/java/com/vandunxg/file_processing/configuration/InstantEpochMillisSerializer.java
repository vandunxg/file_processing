package com.vandunxg.file_processing.configuration;

import java.time.Instant;

import org.springframework.boot.jackson.JacksonComponent;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;

@JacksonComponent
public class InstantEpochMillisSerializer extends ValueSerializer<Instant> {

  @Override
  public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
    generator.writeNumber(value.toEpochMilli());
  }
}
