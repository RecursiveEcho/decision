package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
class Evidence {
  final Store store;

  Evidence(Store s) {
    store = s;
  }

  ArrayNode validate(JsonNode items, String problemId, boolean strict, Set<String> supplied) {
    ArrayNode out = Json.arr();
    if (items == null || items.isNull()) return out;
    if (!items.isArray() || items.size() > 50)
      throw new ApiError(400, "INVALID_EVIDENCE", "证据必须为数组，最多50项");
    for (JsonNode e : items)
      try {
        String id = Json.required(e, "sourceId", 36);
        String quote = Json.required(e, "quote", 20000);
        String relation = Json.choice(e, "relation", "supports", "contradicts", "context");
        ObjectNode source = store.get("source", id);
        if (!source.path("problemId").asText().equals(problemId)
            || !source.path("text").asText().contains(quote)
            || (supplied != null && !supplied.contains(id)))
          throw new ApiError(400, "INVALID_EVIDENCE", "引用必须是当前问题的有效来源原文");
        out.add(Json.obj("sourceId", id, "quote", quote, "relation", relation));
      } catch (ApiError ex) {
        if (strict) throw new ApiError(400, "INVALID_EVIDENCE", "引用必须是当前问题的有效来源原文");
      }
    return out;
  }
}
