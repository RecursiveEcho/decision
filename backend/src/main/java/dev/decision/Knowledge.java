package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owner-reviewed knowledge. Proposals never change the old automatic Topic/Wiki pipeline. */
@Component
class Knowledge {
  static final String VERSION = "reviewed-knowledge-v3";
  static final String NOTICE = "仅整理当前有效对话文字和正式资料，并对照有限的已确认知识；不直接读取或导出附件原件。助手发言是讨论记录，不是外部事实证明。";
  final Store s;
  final Config c;
  final Model model;
  private final TransactionTemplate suggestionSavepoint;
  private static final Logger log = LoggerFactory.getLogger(Knowledge.class);

  Knowledge(Store s, Config c, Model model) {
    this.s = s;
    this.c = c;
    this.model = model;
    suggestionSavepoint = new TransactionTemplate(s.tx.getTransactionManager());
    suggestionSavepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
  }

  record Snapshot(String fingerprint, ObjectNode input, ObjectNode coverage) {}

  static void emptyBody(JsonNode body) {
    if (body == null || !body.isObject() || !body.isEmpty())
      throw new ApiError(400, "INVALID_REQUEST", "请求体必须为{}对象");
  }

  ObjectNode request(String problemId, JsonNode body) {
    emptyBody(body);
    return request(problemId, false);
  }

  // Called inside the chat completion transaction, after both messages have their final epoch.
  void suggest(String problemId, String userMessageId, String assistantMessageId) {
    try {
      suggestionSavepoint.executeWithoutResult(
          ignored -> {
            String latestUser =
                s.db.queryForObject(
                    "SELECT id::text FROM records WHERE account_id=?::uuid AND kind='message' AND"
                        + " NOT deleted AND problem_id=?::uuid AND data->>'role'='user' ORDER BY"
                        + " chat_source_seq DESC LIMIT 1",
                    String.class,
                    AccountScope.id(),
                    problemId);
            if (!userMessageId.equals(latestUser)) return;
            ObjectNode job = request(problemId, true);
            if (job == null) return;
            s.db.update(
                "UPDATE records SET data=jsonb_set(data,'{knowledgeSuggestion}',?::jsonb) WHERE"
                    + " id=?::uuid AND account_id=?::uuid",
                Json.obj("jobId", job.path("id")).toString(),
                assistantMessageId,
                AccountScope.id());
          });
    } catch (RuntimeException ex) {
      // A full queue or failed optional write must not roll back the completed answer.
      log.warn(
          "Could not schedule optional knowledge suggestion ({})", ex.getClass().getSimpleName());
    }
  }

  private ObjectNode request(String problemId, boolean onlyNew) {
    Snapshot snapshot = snapshot(problemId);
    if (snapshot.coverage.path("messagesConsidered").asInt() == 0
        && snapshot.coverage.path("sourcesConsidered").asInt() == 0)
      throw new ApiError(400, "NO_KNOWLEDGE_INPUT", "当前没有可整理的有效文字；请先在对话里讨论要保留的内容");
    List<String> candidates =
        s.db.queryForList(
            "SELECT id::text FROM jobs WHERE account_id=?::uuid AND"
                + " input->>'operation'='knowledge_proposal' AND input->>'fingerprint'=? AND state"
                + " IN ('queued','running','succeeded') ORDER BY created_at DESC,id DESC",
            String.class,
            AccountScope.id(),
            snapshot.fingerprint);
    for (String id : candidates) {
      ObjectNode job = s.get("job", id);
      if (!job.path("status").asText().equals("succeeded")) return onlyNew ? null : job;
      String proposalId = job.path("result").path("proposalId").asText();
      if (!proposalId.isBlank()
          && s.get("knowledge_proposal", proposalId).path("status").asText().equals("pending"))
        return onlyNew ? null : job;
    }
    return s.job(
        "knowledge_proposal",
        problemId,
        Json.obj("problemId", problemId, "fingerprint", snapshot.fingerprint),
        c.mode());
  }

  void run(ObjectNode work) {
    String p = work.path("problemId").asText();
    Snapshot before =
        s.write(
            () -> {
              if (!s.jobRunning(work)) return null;
              Snapshot current = snapshot(p);
              requireFingerprint(work.path("fingerprint").asText(), current);
              return current;
            });
    if (before == null) return;
    ObjectNode output = model.knowledge(p, before.input.deepCopy());
    ObjectNode candidate = validate(output, before);
    s.write(
        () -> {
          if (!s.jobRunning(work)) return null;
          requireFingerprint(before.fingerprint, snapshot(p));
          candidate.put("problemId", p);
          candidate.put("status", "pending");
          candidate.put("contextFingerprint", before.fingerprint);
          candidate.set("coverage", before.coverage.deepCopy());
          ObjectNode proposal = s.create("knowledge_proposal", p, candidate);
          ObjectNode job = s.get("job", work.path("jobId").asText());
          job.put("status", "succeeded");
          job.set("result", Json.obj("proposalId", proposal.path("id")));
          job.putNull("error");
          s.save("job", job);
          s.db.update(
              "UPDATE jobs SET state='succeeded' WHERE id=?::uuid AND account_id=?::uuid",
              job.path("id").asText(),
              AccountScope.id());
          return null;
        });
  }

  Snapshot snapshot(String problemId) {
    return snapshot(s, c, problemId);
  }

  private static Snapshot snapshot(Store s, Config c, String problemId) {
    String p = s.get("problem", problemId).path("id").asText();
    long revision = s.version();
    long epoch = s.memoryEpoch();
    List<ObjectNode> allMessages = s.all("message", p);
    List<ObjectNode> eligibleMessages =
        s.db.query(
            // Linking the derived job must not make its own input snapshot stale.
            "SELECT (data - 'knowledgeSuggestion')::text FROM records WHERE account_id=?::uuid AND"
                + " kind='message' AND NOT deleted AND problem_id=?::uuid AND context_revision=?"
                + " AND memory_epoch=? ORDER BY created_at,id",
            (rs, i) -> (ObjectNode) Json.parse(rs.getString(1)),
            AccountScope.id(),
            p,
            revision,
            epoch);
    List<ObjectNode> allSources = s.all("source", p);
    List<ObjectNode> allKnowledge = s.all("knowledge_entry", null);
    ArrayNode messages = Json.arr(), sources = Json.arr(), knowledge = Json.arr();
    boolean clipped = false;
    int budget = 48000;
    List<ObjectNode> newest = new ArrayList<>(eligibleMessages);
    Collections.reverse(newest);
    List<ObjectNode> chosen = new ArrayList<>();
    for (ObjectNode m : newest) {
      if (chosen.size() >= 24) break;
      String role = m.path("role").asText(), text = m.path("content").asText();
      if (!Set.of("user", "assistant").contains(role) || text.isBlank()) continue;
      int length = Math.min(text.length(), Math.min(6000, budget));
      if (length == 0) break;
      clipped |= length < text.length();
      chosen.add(Json.obj("id", m.path("id"), "role", role, "content", text.substring(0, length)));
      budget -= length;
    }
    Collections.reverse(chosen);
    chosen.forEach(messages::add);
    newest = new ArrayList<>(allSources);
    Collections.reverse(newest);
    budget = 48000;
    for (ObjectNode src : newest) {
      if (sources.size() >= 8 || budget == 0) break;
      String text = src.path("text").asText();
      if (text.isBlank() || src.path("status").asText().equals("failed")) continue;
      int length = Math.min(text.length(), Math.min(8000, budget));
      clipped |= length < text.length();
      sources.add(
          Json.obj(
              "id",
              src.path("id"),
              "title",
              src.path("title"),
              "kind",
              src.path("kind"),
              "text",
              text.substring(0, length)));
      budget -= length;
    }
    String query = messages.toString() + sources.toString();
    budget = 48000;
    for (ObjectNode entry : ranked(allKnowledge, query)) {
      if (knowledge.size() >= 12) break;
      int length = entry.path("markdown").asText().length();
      if (length > budget || !liveReferences(s, entry.path("refs"))) continue;
      knowledge.add(entry.deepCopy());
      budget -= length;
    }
    ObjectNode coverage =
        Json.obj(
            "messagesTotal",
            allMessages.size(),
            "messagesConsidered",
            messages.size(),
            "sourcesTotal",
            allSources.size(),
            "sourcesConsidered",
            sources.size(),
            "knowledgeTotal",
            allKnowledge.size(),
            "knowledgeConsidered",
            knowledge.size(),
            "truncated",
            clipped
                || messages.size() < allMessages.size()
                || sources.size() < allSources.size()
                || knowledge.size() < allKnowledge.size(),
            "attachmentsIncluded",
            false,
            "notice",
            NOTICE);
    String fingerprint =
        Auth.hash(
            Json.canonical(
                    Json.obj(
                        "version",
                        VERSION,
                        "model",
                        c.modelName,
                        "mode",
                        c.mode(),
                        "problemId",
                        p,
                        "revision",
                        revision,
                        "epoch",
                        epoch,
                        "messages",
                        eligibleMessages,
                        "sources",
                        allSources,
                        "knowledge",
                        allKnowledge))
                .toString());
    return new Snapshot(
        fingerprint,
        Json.obj(
            "messages", messages, "sources", sources, "knowledge", knowledge, "coverage", coverage),
        coverage);
  }

  static List<ObjectNode> ranked(List<ObjectNode> entries, String query) {
    Set<String> terms = terms(query);
    List<ObjectNode> result = new ArrayList<>(entries);
    result.sort(
        Comparator.<ObjectNode>comparingInt(
                n -> {
                  Set<String> match =
                      terms(
                          n.path("title").asText()
                              + " "
                              + n.path("domains")
                              + " "
                              + n.path("markdown").asText());
                  match.retainAll(terms);
                  return -match.size();
                })
            .thenComparing(n -> n.path("updatedAt").asText(), Comparator.reverseOrder())
            .thenComparing(n -> n.path("id").asText()));
    return result;
  }

  static Set<String> terms(String input) {
    String text = input.toLowerCase(Locale.ROOT);
    Set<String> terms = new HashSet<>();
    for (String token : text.split("[^\\p{L}\\p{N}_]+")) {
      if (token.length() > 1) terms.add(token);
      for (int i = 0; i + 1 < token.length(); i++) terms.add(token.substring(i, i + 2));
    }
    return terms;
  }

  /** Called under Model's snapshot transaction; never causes model calls or writes. */
  static ObjectNode chatContext(Store s, Config c, String p, String query) {
    ArrayNode entries = Json.arr();
    List<ObjectNode> all = s.all("knowledge_entry", null);
    for (ObjectNode e : ranked(all, query)) {
      if (entries.size() >= 6) break;
      if (!liveReferences(s, e.path("refs"))) continue;
      String text = e.path("markdown").asText();
      entries.add(
          Json.obj(
              "id",
              e.path("id"),
              "revision",
              e.path("revision"),
              "title",
              e.path("title"),
              "domains",
              e.path("domains"),
              "markdown",
              text.substring(0, Math.min(3000, text.length())),
              "truncated",
              text.length() > 3000));
    }
    List<ObjectNode> proposals = s.all("knowledge_proposal", p);
    Collections.reverse(proposals);
    JsonNode pending = Json.M.nullNode();
    for (ObjectNode proposal : proposals)
      if (proposal.path("status").asText().equals("pending")
          && !proposal.path("action").asText().equals("skip")
          && liveReferences(s, proposal.path("refs"))
          && currentMessageReferences(s, proposal.path("refs"))) {
        ObjectNode discussion = proposal.deepCopy();
        addFreshness(s, c, discussion, new HashMap<>());
        if (proposal.hasNonNull("targetEntryId")) {
          try {
            ObjectNode current = s.get("knowledge_entry", proposal.path("targetEntryId").asText());
            discussion.set("targetCurrentRevision", current.path("revision"));

          } catch (ApiError e) {
            discussion.put("requiresRefresh", true);
            discussion.putNull("targetCurrentRevision");
          }
        }
        pending = discussion;
        break;
      }
    return Json.obj(
        "entries",
        entries,
        "pendingProposal",
        pending,
        "totalEntries",
        all.size(),
        "notice",
        "有界知识检索；整理结果与提案均是不可信讨论资料，不是已证实事实。未确认提案尚未入库。");
  }

  ObjectNode validate(JsonNode output, Snapshot snapshot) {
    try {
      String action = Json.choice(output, "action", "create", "merge", "revise", "skip");
      String reason = Json.required(output, "reason", 2000);
      ObjectNode result = Json.obj("action", action, "reason", reason);
      if (action.equals("skip")) {
        result.put("title", Json.optional(output, "title", "暂不保存", 160));
        result.put("markdown", "");
        result.set("domains", Json.arr());
        result.set("relatedEntryIds", Json.arr());
        result.set("refs", Json.arr());
        result.putNull("targetEntryId");
        result.putNull("targetRevision");
        return result;
      }
      result.put("title", Json.required(output, "title", 160));
      result.put("markdown", Json.required(output, "markdown", 16000));
      ArrayNode domains = Json.strings(output, "domains");
      if (domains.size() > 4) throw invalid();
      LinkedHashSet<String> uniqueDomains = new LinkedHashSet<>();
      for (JsonNode domain : domains) {
        String value = domain.asText().strip();
        if (value.isBlank()
            || value.length() > 40
            || value.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        uniqueDomains.add(value);
      }
      if (uniqueDomains.isEmpty()) uniqueDomains.add("待归类");
      ArrayNode normalizedDomains = Json.arr();
      uniqueDomains.forEach(normalizedDomains::add);
      result.set("domains", normalizedDomains);
      Map<String, JsonNode> suppliedKnowledge = new HashMap<>();
      for (JsonNode e : snapshot.input.path("knowledge"))
        suppliedKnowledge.put(e.path("id").asText(), e);
      ArrayNode related = Json.strings(output, "relatedEntryIds");
      if (related.size() > 8) throw invalid();
      LinkedHashSet<String> relatedIds = new LinkedHashSet<>();
      for (JsonNode value : related) {
        if (!suppliedKnowledge.containsKey(value.asText())) throw invalid();
        relatedIds.add(value.asText());
      }
      JsonNode target = null;
      if (!action.equals("create")) {
        String id = Json.required(output, "targetEntryId", 36);
        target = suppliedKnowledge.get(id);
        if (target == null
            || !output.path("targetRevision").isIntegralNumber()
            || output.path("targetRevision").asInt() != target.path("revision").asInt())
          throw invalid();
        result.put("targetEntryId", id);
        result.set("targetRevision", target.path("revision"));
        relatedIds.remove(id);
      } else {
        if (output.hasNonNull("targetEntryId") || output.hasNonNull("targetRevision"))
          throw invalid();
        result.putNull("targetEntryId");
        result.putNull("targetRevision");
      }
      ArrayNode normalizedRelated = Json.arr();
      relatedIds.forEach(normalizedRelated::add);
      result.set("relatedEntryIds", normalizedRelated);
      Map<String, String> suppliedText = new HashMap<>();
      Map<String, String> suppliedRoles = new HashMap<>();
      for (JsonNode m : snapshot.input.path("messages")) {
        suppliedText.put("message:" + m.path("id").asText(), m.path("content").asText());
        suppliedRoles.put(m.path("id").asText(), m.path("role").asText());
      }
      for (JsonNode src : snapshot.input.path("sources"))
        suppliedText.put("source:" + src.path("id").asText(), src.path("text").asText());
      Set<String> currentReferences = new HashSet<>(suppliedText.keySet());
      for (JsonNode e : snapshot.input.path("knowledge"))
        for (JsonNode ref : e.path("refs")) {
          String key = ref.path("kind").asText() + ":" + ref.path("id").asText();
          suppliedText.merge(key, ref.path("quote").asText(), (a, b) -> a + "\n\n" + b);
          if (ref.path("kind").asText().equals("message"))
            suppliedRoles.put(ref.path("id").asText(), ref.path("role").asText());
        }
      JsonNode refs = output.path("refs");
      if (!refs.isArray() || refs.isEmpty() || refs.size() > 16) throw invalid();
      ArrayNode normalizedRefs = Json.arr();
      Set<String> seen = new HashSet<>();
      boolean citesCurrent = false;
      for (JsonNode ref : refs) {
        String kind = Json.choice(ref, "kind", "message", "source");
        String id = Json.uuid(Json.required(ref, "id", 36));
        String quote = Json.required(ref, "quote", 500);
        String text = suppliedText.get(kind + ":" + id);
        if (text == null || !text.contains(quote)) throw invalid();
        ObjectNode value = Json.obj("kind", kind, "id", id, "quote", quote);
        if (kind.equals("message")) {
          String role = suppliedRoles.get(id);
          if (!ref.path("role").asText().equals(role)) throw invalid();
          value.put("role", role);
        }
        if (seen.add(value.toString())) normalizedRefs.add(value);
        citesCurrent |= currentReferences.contains(kind + ":" + id);
      }
      if (!citesCurrent) throw invalid();
      if (action.equals("merge")) {
        for (JsonNode ref : target.path("refs"))
          if (seen.add(ref.toString())) normalizedRefs.add(ref.deepCopy());
      }
      if (!liveReferences(s, normalizedRefs)) throw invalid();
      result.set("refs", normalizedRefs);
      return result;
    } catch (ApiError error) {
      if (error.code.equals("KNOWLEDGE_MODEL_INVALID")) throw error;
      throw invalid();
    }
  }

  static ApiError invalid() {
    return new ApiError(502, "KNOWLEDGE_MODEL_INVALID", "整理提案格式、目标版本或原文出处无法核验；未写入知识，可明确重新整理", true);
  }

  void requireFingerprint(String expected, Snapshot current) {
    if (!expected.equals(current.fingerprint))
      throw new ApiError(409, "KNOWLEDGE_CHANGED", "对话、资料或已有知识已变化，请重新整理后再确认", true);
  }

  // Project freshness on read; keep draft content and confirm-time validation independent.
  void addFreshness(ObjectNode proposal, Map<String, String> fingerprints) {
    addFreshness(s, c, proposal, fingerprints);
  }

  private static void addFreshness(
      Store s, Config c, ObjectNode proposal, Map<String, String> fingerprints) {
    if (!proposal.path("status").asText().equals("pending")) return;
    String p = proposal.path("problemId").asText();
    // The fingerprint includes all confirmed entries and their current target revisions.
    String current = fingerprints.computeIfAbsent(p, key -> snapshot(s, c, key).fingerprint);
    proposal.put(
        "requiresRefresh",
        !current.equals(proposal.path("contextFingerprint").asText())
            || !liveReferences(s, proposal.path("refs")));
  }

  ObjectNode accept(String id, JsonNode body) {
    if (body == null || !body.isObject() || body.size() != 1 || !body.has("expectedTargetRevision"))
      throw new ApiError(400, "INVALID_REQUEST", "确认需提交 expectedTargetRevision，新增时为null");
    ObjectNode proposal = s.get("knowledge_proposal", id);
    JsonNode expected = body.path("expectedTargetRevision");
    if (!(expected.isNull() || expected.isIntegralNumber())
        || !expected.equals(proposal.path("targetRevision")))
      throw new ApiError(409, "KNOWLEDGE_CHANGED", "确认版本与提案不符，请重新查看提案", true);
    if (proposal.path("status").asText().equals("accepted")) return accepted(proposal);
    if (!proposal.path("status").asText().equals("pending"))
      throw new ApiError(409, "KNOWLEDGE_CHANGED", "此提案已略过，请重新整理", true);
    if (proposal.path("action").asText().equals("skip"))
      throw new ApiError(409, "KNOWLEDGE_SKIP", "此提案建议暂不保存，没有可确认的知识");
    requireFingerprint(
        proposal.path("contextFingerprint").asText(),
        snapshot(proposal.path("problemId").asText()));
    if (!liveReferences(s, proposal.path("refs")))
      throw new ApiError(409, "KNOWLEDGE_CHANGED", "出处已更正或不可用，请重新整理", true);
    ObjectNode entry = Json.obj();
    for (String field :
        List.of("problemId", "title", "markdown", "domains", "relatedEntryIds", "refs", "coverage"))
      entry.set(field, proposal.path(field).deepCopy());
    entry.put("proposalId", proposal.path("id").asText());
    if (proposal.hasNonNull("targetEntryId")) {
      ObjectNode old = s.get("knowledge_entry", proposal.path("targetEntryId").asText());
      if (old.path("revision").asInt() != proposal.path("targetRevision").asInt())
        throw new ApiError(409, "KNOWLEDGE_CHANGED", "目标知识已有新版本，请重新整理", true);
      entry.put("id", old.path("id").asText());
      entry.set("createdAt", old.path("createdAt"));
      entry.put("revision", old.path("revision").asInt() + 1);
      s.save("knowledge_entry", entry);
    } else {
      entry.put("revision", 1);
      s.create("knowledge_entry", proposal.path("problemId").asText(), entry);
    }
    proposal.put("status", "accepted");
    proposal.put("entryId", entry.path("id").asText());
    s.save("knowledge_proposal", proposal);
    return accepted(proposal);
  }

  ObjectNode accepted(ObjectNode proposal) {
    return Json.obj(
        "proposalId",
        proposal.path("id"),
        "entryId",
        proposal.path("entryId"),
        "status",
        "accepted",
        "action",
        proposal.path("action"));
  }

  ObjectNode dismiss(String id, JsonNode body) {
    emptyBody(body);
    ObjectNode proposal = s.get("knowledge_proposal", id);
    if (proposal.path("status").asText().equals("accepted"))
      throw new ApiError(409, "KNOWLEDGE_CHANGED", "已入库的提案不能略过");
    if (!proposal.path("status").asText().equals("dismissed")) {
      proposal.put("status", "dismissed");
      s.save("knowledge_proposal", proposal);
    }
    return Json.obj("proposalId", proposal.path("id"), "status", "dismissed");
  }

  static boolean liveReferences(Store s, JsonNode refs) {
    for (JsonNode ref : refs) {
      try {
        String kind = ref.path("kind").asText();
        ObjectNode original = s.get(kind, ref.path("id").asText());
        String text = original.path(kind.equals("source") ? "text" : "content").asText();
        if (!text.contains(ref.path("quote").asText())) return false;
        if (kind.equals("message") && !original.path("role").equals(ref.path("role"))) return false;
      } catch (ApiError e) {
        return false;
      }
    }
    return true;
  }

  private static boolean currentMessageReferences(Store s, JsonNode refs) {
    long epoch = s.memoryEpoch();
    for (JsonNode ref : refs)
      if (ref.path("kind").asText().equals("message")
          && s.db.queryForObject(
                  "SELECT count(*) FROM records WHERE account_id=?::uuid AND id=?::uuid AND NOT"
                      + " deleted AND context_revision=? AND memory_epoch=?",
                  Integer.class,
                  AccountScope.id(),
                  ref.path("id").asText(),
                  s.version(),
                  epoch)
              != 1) return false;
    return true;
  }

  ObjectNode history(String id) {
    s.get("knowledge_entry", id);
    ArrayNode versions = Json.arr();
    s.db
        .queryForList(
            "SELECT h.data::text FROM record_history h JOIN records r ON r.id=h.record_id WHERE"
                + " r.account_id=?::uuid AND h.kind='knowledge_entry' AND h.record_id=?::uuid ORDER"
                + " BY (h.data->>'revision')::int DESC,h.seq DESC",
            String.class,
            AccountScope.id(),
            id)
        .forEach(raw -> versions.add(Json.parse(raw)));
    return Json.obj("items", versions, "nextCursor", null);
  }

  ObjectNode export() {
    return s.write(
        () -> {
          ArrayNode entries = Json.arr(),
              histories = Json.arr(),
              references = Json.arr(),
              domains = Json.arr();
          TreeSet<String> domainNames = new TreeSet<>();
          Map<String, ObjectNode> referenceMap = new LinkedHashMap<>();
          for (ObjectNode entry : s.all("knowledge_entry", null)) {
            entries.add(entry);
            entry.path("domains").forEach(d -> domainNames.add(d.asText()));
            collectReferences(entry.path("refs"), referenceMap);
            ArrayNode versions = (ArrayNode) history(entry.path("id").asText()).path("items");
            histories.add(Json.obj("entryId", entry.path("id"), "versions", versions));
            for (JsonNode version : versions) collectReferences(version.path("refs"), referenceMap);
          }
          domainNames.forEach(domains::add);
          referenceMap.values().forEach(references::add);
          return Json.obj(
              "version",
              1,
              "generatedAt",
              Json.now(),
              "entries",
              entries,
              "histories",
              histories,
              "references",
              references,
              "domains",
              domains);
        });
  }

  private void collectReferences(JsonNode refs, Map<String, ObjectNode> result) {
    for (JsonNode ref : refs) {
      String kind = ref.path("kind").asText(), id = ref.path("id").asText();
      String key = kind + ":" + id;
      String quote = ref.path("quote").asText();
      ObjectNode value = result.get(key);
      if (value != null) {
        String previous = value.path("quote").asText();
        if (!previous.contains(quote)) value.put("quote", previous + "\n\n" + quote);
        continue;
      }
      value =
          Json.obj(
              "kind",
              kind,
              "id",
              id,
              "quote",
              quote,
              "title",
              kind.equals("message") ? "对话出处" : "资料出处",
              "problemId",
              null);
      if (ref.has("role")) value.set("role", ref.path("role"));
      try {
        ObjectNode original = s.get(kind, id);
        value.set("problemId", original.path("problemId"));
        if (kind.equals("source")) {
          value.set("title", original.path("title"));
          value.set("originUrl", original.path("originUrl"));
        }
      } catch (ApiError e) {
        value.put("title", "出处已不可用（保留确认时引用）");
      }
      result.put(key, value);
    }
  }
}
