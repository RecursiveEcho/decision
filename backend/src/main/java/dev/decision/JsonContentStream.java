package dev.decision;

import java.util.function.Consumer;

/**
 * Incrementally decodes only the top-level JSON content string; metadata remains private until
 * validation.
 */
final class JsonContentStream {
  private final Consumer<String> sink;
  private int depth, unicodeDigits, unicodeValue;
  private boolean inString, escaped, keyString, target, expectKey, expectValue, seenContent;
  private String key = "";
  private final StringBuilder token = new StringBuilder(), output = new StringBuilder();
  private char highSurrogate;

  JsonContentStream(Consumer<String> sink) {
    this.sink = sink;
  }

  void accept(String fragment) {
    for (int i = 0; i < fragment.length(); i++) {
      char c = fragment.charAt(i);
      if (inString) {
        if (unicodeDigits > 0) {
          int digit = Character.digit(c, 16);
          if (digit < 0) throw invalid();
          unicodeValue = unicodeValue * 16 + digit;
          if (--unicodeDigits == 0) decoded((char) unicodeValue);
        } else if (escaped) {
          escaped = false;
          switch (c) {
            case 'u' -> {
              unicodeDigits = 4;
              unicodeValue = 0;
            }
            case '"', '\\', '/' -> decoded(c);
            case 'b' -> decoded('\b');
            case 'f' -> decoded('\f');
            case 'n' -> decoded('\n');
            case 'r' -> decoded('\r');
            case 't' -> decoded('\t');
            default -> throw invalid();
          }
        } else if (c == '\\') escaped = true;
        else if (c == '"') {
          if (highSurrogate != 0) throw invalid();
          inString = false;
          if (keyString) {
            key = token.toString();
            expectKey = false;
          }
          if (target) target = false;
          keyString = false;
        } else {
          if (c < 32) throw invalid();
          decoded(c);
        }
        continue;
      }
      if (c == '"') {
        inString = true;
        token.setLength(0);
        keyString = depth == 1 && expectKey;
        target = depth == 1 && expectValue && key.equals("content");
        if (target) {
          if (seenContent) throw invalid();
          seenContent = true;
        }
        expectValue = false;
      } else if (c == '{' || c == '[') {
        depth++;
        if (depth == 1 && c == '{') expectKey = true;
        expectValue = false;
      } else if (c == '}' || c == ']') {
        depth--;
        if (depth < 0) throw invalid();
      } else if (c == ':' && depth == 1) expectValue = true;
      else if (c == ',' && depth == 1) {
        expectKey = true;
        expectValue = false;
      } else if (!Character.isWhitespace(c)) expectValue = false;
    }
    if (!output.isEmpty()) {
      sink.accept(output.toString());
      output.setLength(0);
    }
  }

  private void decoded(char c) {
    if (keyString) token.append(c);
    if (!target) return;
    if (highSurrogate != 0) {
      if (!Character.isLowSurrogate(c)) throw invalid();
      output.append(highSurrogate).append(c);
      highSurrogate = 0;
    } else if (Character.isHighSurrogate(c)) highSurrogate = c;
    else if (Character.isLowSurrogate(c)) throw invalid();
    else output.append(c);
  }

  private ApiError invalid() {
    return new ApiError(502, "MODEL_INVALID_JSON", "模型返回了无效的流式 JSON；已保留收到的片段", true);
  }
}
