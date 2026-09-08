package com.vandunxg.file_processing.testsupport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;

/** Stands in for object storage so a test owns the bytes and can make reads fail on demand. */
public class InMemoryFileStorage implements FileStorage {

  private final Map<String, byte[]> objects = new HashMap<>();
  private boolean readsFail;
  private Runnable onRead = () -> {};

  public void put(String key, String content) {
    objects.put(key, content.getBytes(StandardCharsets.UTF_8));
  }

  public String read(String key) {
    return new String(objects.get(key), StandardCharsets.UTF_8);
  }

  public Set<String> keys() {
    return objects.keySet();
  }

  public void clear() {
    objects.clear();
    readsFail = false;
    onRead = () -> {};
  }

  public void failReads() {
    readsFail = true;
  }

  public void succeedReads() {
    readsFail = false;
  }

  /** Runs once when the worker opens the original, letting a test interleave with the run. */
  public void onRead(Runnable hook) {
    this.onRead = hook;
  }

  @Override
  public StoredObject store(
      String storageKey, String contentType, long contentLength, InputStream content) {
    try (content) {
      ByteArrayOutputStream buffer = new ByteArrayOutputStream();
      content.transferTo(buffer);
      byte[] stored = buffer.toByteArray();
      objects.put(storageKey, stored);
      return new StoredObject("file-processing", stored.length, sha256(stored), contentType);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Override
  public InputStream open(String storageKey) {
    if (readsFail) {
      throw new IllegalStateException("storage unavailable: secret bucket-internal detail");
    }
    onRead.run();
    byte[] content = objects.get(storageKey);
    if (content == null) {
      throw new IllegalStateException("object not found");
    }
    return new ByteArrayInputStream(content);
  }

  @Override
  public void delete(String storageKey) {
    objects.remove(storageKey);
  }

  /** A real digest, so identical bytes really do collide the way the duplicate rule expects. */
  private static String sha256(byte[] content) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
