package dev.decision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class JsonContentStreamTest {
  @Test
  void decodesEscapesAcrossEveryBoundaryAndHidesMetadata() throws Exception {
    String value = "中文\n引号\"反斜线\\与😀";
    String raw =
        "{\"metadata\":{\"content\":\"private\"},\"content\":"
            + Json.M.writeValueAsString(value)
            + ",\"memoryActions\":[{\"content\":\"hidden\"}]}";
    for (int size = 1; size < raw.length(); size++) {
      List<String> pieces = new ArrayList<>();
      var decoder = new JsonContentStream(pieces::add);
      for (int i = 0; i < raw.length(); i += size)
        decoder.accept(raw.substring(i, Math.min(i + size, raw.length())));
      assertEquals(value, String.join("", pieces));
    }
  }

  @Test
  void decodesEscapedSurrogatesWithoutEmittingHalfPairs() {
    List<String> pieces = new ArrayList<>();
    var decoder = new JsonContentStream(pieces::add);
    decoder.accept("{\"content\":\"\\uD83D");
    assertTrue(pieces.isEmpty());
    decoder.accept("\\uDE00\\n\\t\\\"\"}");
    assertEquals("😀\n\t\"", String.join("", pieces));
  }

  @Test
  void rejectsDuplicateContentAndInvalidEscape() {
    var decoder = new JsonContentStream(s -> {});
    assertThrows(ApiError.class, () -> decoder.accept("{\"content\":\"one\",\"content\":\"two\"}"));
    assertThrows(
        ApiError.class, () -> new JsonContentStream(s -> {}).accept("{\"content\":\"\\q\"}"));
  }

  @Test
  void adaptsOpenAiParametersWithoutDeepSeekFields() {
    var payload =
        Json.obj(
            "model",
            "old",
            "thinking",
            Json.obj("type", "enabled"),
            "reasoning_effort",
            "max",
            "max_tokens",
            4000);
    ModelSettings.adapt(payload, new ModelSettings.Profile("openai", "gpt-test", "", ""));
    assertFalse(payload.has("thinking"));
    assertFalse(payload.has("max_tokens"));
    assertEquals("high", payload.path("reasoning_effort").asText());
    assertEquals(4000, payload.path("max_completion_tokens").asInt());
    var unsupported = Json.obj("thinking", Json.obj("type", "enabled"), "reasoning_effort", "low");
    assertEquals(
        "MODEL_CAPABILITY_UNSUPPORTED",
        assertThrows(
                ApiError.class,
                () ->
                    ModelSettings.adapt(
                        unsupported, new ModelSettings.Profile("openai", "gpt-4.1-mini", "", "")))
            .code);
  }
}
