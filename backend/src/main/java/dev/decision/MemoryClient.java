package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** Stable memory boundary. This release installs only the existing local implementation. */
interface MemoryClient {
  ChatMemory.Snapshot retrieve(String problemId, String content);
  void requireCurrent(ChatMemory.Snapshot snapshot);
  ArrayNode apply(String problemId, String userMessageId, JsonNode actions, ChatMemory.Snapshot snapshot);
  long currentEpoch();
}
