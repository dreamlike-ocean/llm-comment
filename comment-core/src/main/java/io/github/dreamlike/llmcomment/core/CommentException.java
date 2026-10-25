package io.github.dreamlike.llmcomment.core;

import java.util.Map;

public class CommentException extends RuntimeException {
  private final String code;
  private final Map<String, Object> details;

  public CommentException(String code, String message) {
    this(code, message, Map.of(), null);
  }

  public CommentException(String code, String message, Throwable cause) {
    this(code, message, Map.of(), cause);
  }

  public CommentException(String code, String message, Map<String, Object> details) {
    this(code, message, details, null);
  }

  public CommentException(
      String code, String message, Map<String, Object> details, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.details = Map.copyOf(details);
  }

  public String code() {
    return code;
  }

  public Map<String, Object> details() {
    return details;
  }
}
