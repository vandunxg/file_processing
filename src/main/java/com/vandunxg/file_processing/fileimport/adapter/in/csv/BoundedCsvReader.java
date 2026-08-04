package com.vandunxg.file_processing.fileimport.adapter.in.csv;

import java.io.IOException;
import java.io.Reader;

final class BoundedCsvReader extends Reader {

  private final Reader delegate;
  private final int maximumFieldCharacters;
  private int fieldCharacters;
  private int fieldCount = 1;
  private boolean discardingField;
  private boolean discardingRecord;
  private boolean inQuotes;
  private boolean quotePending;

  BoundedCsvReader(Reader delegate, int maximumFieldCharacters) {
    this.delegate = delegate;
    this.maximumFieldCharacters = maximumFieldCharacters;
  }

  @Override
  public int read() throws IOException {
    while (true) {
      int value = delegate.read();
      if (value == -1 || forward((char) value)) {
        return value;
      }
    }
  }

  @Override
  public int read(char[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    int read = 0;
    while (read < length) {
      int value = read();
      if (value == -1) {
        return read == 0 ? -1 : read;
      }
      buffer[offset + read++] = (char) value;
    }
    return read;
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }

  private boolean forward(char value) {
    if (quotePending) {
      if (value == '"') {
        quotePending = false;
        return true;
      }
      inQuotes = false;
      quotePending = false;
    }
    if (inQuotes) {
      if (value == '"') {
        quotePending = true;
        return true;
      }
      return forwardFieldCharacter();
    }
    if (discardingRecord) {
      if (value == '\n' || value == '\r') {
        resetRecord();
        return true;
      }
      return false;
    }
    if (discardingField) {
      if (value == ',') {
        resetField();
        return true;
      }
      if (value == '\n' || value == '\r') {
        resetRecord();
        return true;
      }
      return false;
    }
    if (value == '"') {
      inQuotes = true;
      return true;
    }
    if (value == ',' || value == '\n' || value == '\r') {
      if (value == ',') {
        resetField();
        if (fieldCount++ == 6) {
          discardingRecord = true;
        }
      } else {
        resetRecord();
      }
      return true;
    }
    return forwardFieldCharacter();
  }

  private boolean forwardFieldCharacter() {
    if (++fieldCharacters <= maximumFieldCharacters) {
      return true;
    }
    discardingField = true;
    return false;
  }

  private void resetField() {
    fieldCharacters = 0;
    discardingField = false;
  }

  private void resetRecord() {
    resetField();
    fieldCount = 1;
    discardingRecord = false;
  }
}
