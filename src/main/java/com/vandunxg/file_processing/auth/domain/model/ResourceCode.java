package com.vandunxg.file_processing.auth.domain.model;

public enum ResourceCode {
  ALL("SYSTEM"),
  USER("IDENTITY"),
  ROLE("IDENTITY"),
  SESSION("IDENTITY"),
  AUDIT("IDENTITY"),
  FILE("FILE_PROCESSING"),
  JOB("FILE_PROCESSING"),
  REPORT("FILE_PROCESSING"),
  CUSTOMER("CUSTOMER");

  private final String group;

  ResourceCode(String group) {
    this.group = group;
  }

  public String getGroup() {
    return group;
  }
}
