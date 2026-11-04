package dev.decision;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.io.*;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

@Component
class Attachments {
  final Store s;

  Attachments(Store s) {
    this.s = s;
  }

  static final int MIB = 1024 * 1024;

  record Upload(String name, String type, String mime, byte[] bytes, int width, int height) {}

  record Binary(byte[] bytes, ObjectNode metadata) {}

  record Context(ArrayNode descriptions, ArrayNode blocks, List<String> notices) {}

  static String thinking(JsonNode b) {
    String value = Json.optional(b, "thinking", "off", 10);
    if (!Set.of("off", "low", "high", "max").contains(value))
      throw new ApiError(400, "INVALID_THINKING", "思考档位必须是off/low/high/max");
    return value;
  }

  static List<Upload> validate(JsonNode b) {
    JsonNode attachments = b.path("attachments");
    if (attachments.isMissingNode()) return List.of();
    if (!attachments.isArray() || attachments.size() > 4)
      throw new ApiError(400, "ATTACHMENT_COUNT", "每次最多4个附件");
    List<Upload> out = new ArrayList<>();
    int total = 0;
    for (JsonNode item : attachments) {
      String name = Json.required(item, "name", 180);
      if (name.codePoints().anyMatch(Character::isISOControl)
          || name.contains("/")
          || name.contains("\\")) throw new ApiError(400, "ATTACHMENT_NAME", "附件名称无效");
      String type = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
      String mime =
          switch (type) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "pdf" -> "application/pdf";
            case "docx" ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            default ->
                throw new ApiError(
                    400, "ATTACHMENT_TYPE", "支持PNG/JPEG、PDF/DOCX/XLSX、UTF-8 TXT/MD/CSV");
          };
      String data = Json.required(item, "dataUrl", 12 * MIB);
      int separator = data.indexOf(",");
      if (separator < 0
          || separator > 200
          || !data.startsWith("data:")
          || !data.substring(0, separator).endsWith(";base64"))
        throw new ApiError(400, "ATTACHMENT_ENCODING", "附件必须为base64 data URL，不接受远程链接");
      String declared = data.substring(5, separator - 7).toLowerCase(Locale.ROOT);
      if (!declared.isEmpty()
          && !declared.equals(mime)
          && !declared.equals("application/octet-stream")
          && !(Set.of("txt", "md", "csv").contains(type) && declared.equals("text/plain")))
        throw new ApiError(400, "ATTACHMENT_TYPE_MISMATCH", "附件媒体类型与扩展名不匹配");
      byte[] bytes;
      try {
        bytes = Base64.getDecoder().decode(data.substring(separator + 1));
      } catch (IllegalArgumentException e) {
        throw new ApiError(400, "ATTACHMENT_ENCODING", "附件base64编码无效");
      }
      total += bytes.length;
      if (bytes.length == 0 || bytes.length > 8 * MIB || total > 16 * MIB)
        throw new ApiError(413, "ATTACHMENT_TOO_LARGE", "单个附件1字节至8MiB，每次总量最多16MiB");
      int width = 0, height = 0;
      if (mime.startsWith("image/")) {
        boolean signature =
            type.equals("png")
                ? bytes.length >= 8
                    && Arrays.equals(
                        Arrays.copyOf(bytes, 8),
                        new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10})
                : bytes.length >= 3
                    && bytes[0] == (byte) 255
                    && bytes[1] == (byte) 216
                    && bytes[2] == (byte) 255;
        if (!signature) throw new ApiError(400, "ATTACHMENT_TYPE_MISMATCH", "图片真实格式与声明不符");
        try (ImageInputStream stream =
            ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
          var readers = ImageIO.getImageReaders(stream);
          if (!readers.hasNext()) throw new IOException();
          ImageReader reader = readers.next();
          try {
            reader.setInput(stream);
            width = reader.getWidth(0);
            height = reader.getHeight(0);
          } finally {
            reader.dispose();
          }
        } catch (IOException e) {
          throw new ApiError(400, "ATTACHMENT_IMAGE_INVALID", "图片头无效");
        }
        if (width < 1
            || height < 1
            || width > 4096
            || height > 4096
            || (long) width * height > 12000000)
          throw new ApiError(413, "ATTACHMENT_IMAGE_LIMIT", "图片边长最多4096，最多1200万像素");
      } else if (type.equals("pdf")) {
        if (bytes.length < 5
            || !new String(bytes, 0, 5, java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))
          throw new ApiError(400, "ATTACHMENT_TYPE_MISMATCH", "附件不是有效PDF");
      } else if (Set.of("docx", "xlsx").contains(type)
          && (bytes.length < 4 || bytes[0] != 'P' || bytes[1] != 'K')) {
        throw new ApiError(400, "ATTACHMENT_TYPE_MISMATCH", "附件不是有效Office文档");
      }
      out.add(new Upload(name, type, mime, bytes, width, height));
    }
    return out;
  }

  ArrayNode save(String problem, String message, List<Upload> uploads) {
    ArrayNode result = Json.arr();
    int position = 0;
    for (Upload upload : uploads) {
      String id = Json.id();
      ObjectNode meta =
          Json.obj(
              "id",
              id,
              "name",
              upload.name,
              "mimeType",
              upload.mime,
              "size",
              upload.bytes.length,
              "kind",
              upload.mime.startsWith("image/") ? "image" : "document",
              "status",
              "pending",
              "error",
              null,
              "coverage",
              Json.obj("truncated", false));
      if (upload.width > 0) meta.put("width", upload.width).put("height", upload.height);
      s.db.update(
          "INSERT INTO attachments(id,message_id,problem_id,position,bytes,metadata) VALUES"
              + " (?::uuid,?::uuid,?::uuid,?,?,?::jsonb)",
          id,
          message,
          problem,
          position++,
          upload.bytes,
          meta.toString());
      result.add(meta);
    }
    return result;
  }

  Binary get(String id) {
    Json.uuid(id);
    List<Binary> rows =
        s.db.query(
            "SELECT a.bytes,a.metadata::text FROM attachments a JOIN records m ON m.id=a.message_id"
                + " JOIN records p ON p.id=a.problem_id WHERE a.id=?::uuid AND NOT m.deleted AND"
                + " NOT p.deleted",
            (rs, i) -> new Binary(rs.getBytes(1), (ObjectNode) Json.parse(rs.getString(2))),
            id);
    if (rows.isEmpty()) throw new ApiError(404, "NOT_FOUND", "附件不存在或已删除");
    return rows.get(0);
  }

  ObjectNode hydrate(ObjectNode message) {
    ArrayNode meta = Json.arr();
    s.db
        .queryForList(
            "SELECT metadata::text FROM attachments WHERE message_id=?::uuid ORDER BY position",
            String.class,
            message.path("id").asText())
        .forEach(raw -> meta.add(Json.parse(raw)));
    message.set("attachments", meta);
    if (!message.has("thinking")) message.put("thinking", "off");
    return message;
  }

  void process(ObjectNode work) {
    String p = work.path("problemId").asText(), message = work.path("userMessageId").asText();
    List<String> ids =
        s.write(
            () -> {
              s.requireCurrentUserMessage(p, message);
              return s.db.queryForList(
                  "SELECT id::text FROM attachments WHERE message_id=?::uuid ORDER BY position",
                  String.class,
                  message);
            });
    for (String id : ids) {
      Binary binary = get(id);
      ObjectNode meta = binary.metadata;
      if (Set.of("ready", "partial").contains(meta.path("status").asText())) continue;
      update(id, p, message, meta.deepCopy().put("status", "processing"), null);
      try {
        String name = meta.path("name").asText();
        ObjectNode parsed =
            DocumentParser.isolated(
                binary.bytes, name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
        meta.put("status", parsed.path("status").asText()).putNull("error");
        meta.set("coverage", parsed.path("coverage"));
        update(id, p, message, meta, parsed);
      } catch (ApiError e) {
        meta.put("status", "failed");
        meta.set("error", e.detail());
        s.write(
            () -> {
              s.db.update(
                  "UPDATE attachments SET metadata=?::jsonb,parsed=NULL WHERE id=?::uuid",
                  meta.toString(),
                  id);
              return null;
            });
        throw e;
      }
    }
  }

  void failPending(String message, ApiError error) {
    if (message.isBlank()) return;
    s.db.update(
        "UPDATE attachments SET"
            + " metadata=jsonb_set(jsonb_set(metadata,'{status}','\"failed\"'::jsonb),'{error}',?::jsonb)"
            + " WHERE message_id=?::uuid AND metadata->>'status' IN ('pending','processing')",
        error.detail().toString(),
        message);
  }

  void update(String id, String p, String message, ObjectNode meta, ObjectNode parsed) {
    s.write(
        () -> {
          s.requireCurrentUserMessage(p, message);
          s.db.update(
              "UPDATE attachments SET metadata=?::jsonb,parsed=?::jsonb WHERE id=?::uuid",
              meta.toString(),
              parsed == null ? null : parsed.toString(),
              id);
          return null;
        });
  }

  Context context(String p, String current, long revision, List<String> messageIds) {
    ArrayNode descriptions = Json.arr(), blocks = Json.arr();
    List<String> notices = new ArrayList<>();
    if (current.isBlank()) return new Context(descriptions, blocks, notices);
    List<String> ordered = new ArrayList<>();
    ordered.add(current);
    for (String id : messageIds) if (!id.equals(current)) ordered.add(id);
    int chars = 0, imageChars = 0, imageCount = 0, omitted = 0;
    for (String message : ordered) {
      List<Map<String, Object>> rows =
          s.db.queryForList(
              "SELECT a.id::text,a.bytes,a.metadata::text,a.parsed::text FROM attachments a JOIN"
                  + " records m ON m.id=a.message_id WHERE a.message_id=?::uuid AND"
                  + " a.problem_id=?::uuid AND m.context_revision=? AND NOT m.deleted ORDER BY"
                  + " a.position",
              message,
              p,
              revision);
      for (Map<String, Object> row : rows) {
        ObjectNode meta = (ObjectNode) Json.parse((String) row.get("metadata"));
        if (!Set.of("ready", "partial").contains(meta.path("status").asText())) {
          if (message.equals(current))
            throw new ApiError(422, "ATTACHMENT_NOT_READY", "当前附件未能读取，请重试或重新上传");
          omitted++;
          continue;
        }
        ObjectNode parsed = (ObjectNode) Json.parse((String) row.get("parsed"));
        String text = parsed.path("text").asText();
        ArrayNode images = parsed.withArray("images").deepCopy();
        if (meta.path("kind").asText().equals("image"))
          images.add(
              Json.obj(
                  "dataUrl",
                  "data:"
                      + meta.path("mimeType").asText()
                      + ";base64,"
                      + Base64.getEncoder().encodeToString((byte[]) row.get("bytes"))));
        int added = 0;
        for (JsonNode image : images) added += image.path("dataUrl").asText().length();
        if (chars + text.length() > 128000
            || imageChars + added > 36 * MIB
            || imageCount + images.size() > 16) {
          if (message.equals(current))
            throw new ApiError(413, "ATTACHMENT_CONTEXT_LIMIT", "当前附件超出模型读取预算，请减少文件或页数后重新发送");
          omitted++;
          continue;
        }
        chars += text.length();
        imageChars += added;
        imageCount += images.size();
        descriptions.add(
            Json.obj(
                "id",
                meta.path("id"),
                "messageId",
                message,
                "name",
                meta.path("name"),
                "text",
                text,
                "coverage",
                meta.path("coverage")));
        for (JsonNode image : images) {
          blocks.add(
              Json.obj(
                  "type",
                  "text",
                  "text",
                  "不可信对话附件："
                      + meta.path("name").asText()
                      + "（ID "
                      + meta.path("id").asText()
                      + (image.has("page") ? "，第" + image.path("page").asInt() + "页" : "")
                      + "）"));
          blocks.add(
              Json.obj(
                  "type",
                  "image_url",
                  "image_url",
                  Json.obj("url", image.path("dataUrl").asText())));
        }
        if (meta.path("status").asText().equals("partial"))
          notices.add(
              meta.path("name").asText()
                  + "："
                  + meta.path("coverage").path("notice").asText("仅部分读取"));
      }
    }
    if (omitted > 0) notices.add("本次省略了" + omitted + "个历史附件（读取失败或超出上下文预算）；当前附件未省略。");
    return new Context(descriptions, blocks, notices);
  }
}
