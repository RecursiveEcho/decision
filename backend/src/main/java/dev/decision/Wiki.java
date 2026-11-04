package dev.decision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

@Component
class Wiki {
  final Config c;

  Wiki(Config c) {
    this.c = c;
  }

  record Result(String pageId, String url, String hash) {}

  JsonNode query(String query, ObjectNode vars) {
    JsonNode out =
        Upstream.post(
            c.wikiBase.replaceAll("/+$", "") + "/graphql",
            c.wikiToken,
            Json.obj("query", query, "variables", vars));
    if (out.path("errors").size() > 0)
      throw new ApiError(502, "WIKI_GRAPHQL_ERROR", "Wiki GraphQL 拒绝请求，请核对 2.x 版本和页面读写权限");
    return out.path("data");
  }

  String publicUrl(String path) {
    return (c.wikiPublicUrl.isBlank() ? c.wikiBase : c.wikiPublicUrl).replaceAll("/+$", "")
        + "/"
        + c.wikiLocale
        + "/"
        + path;
  }

  Result sync(ObjectNode topic) {
    if (!c.wikiConfigured()) throw new ApiError(503, "WIKI_NOT_CONFIGURED", "Wiki 尚未配置");
    String path = "decision/" + topic.path("id").asText();
    String text = topic.path("markdown").asText();
    // Recover after a timeout/restart by stable path, before issuing any create mutation.
    JsonNode pages =
        query(
                "query($locale:String!){pages{list(limit:10000,locale:$locale){id path}}}",
                Json.obj("locale", c.wikiLocale))
            .path("pages")
            .path("list");
    if (!pages.isArray() || pages.size() >= 10000)
      throw new ApiError(502, "WIKI_LOOKUP_FAILED", "无法安全确认 Wiki 页面路径");
    Integer id = null;
    for (JsonNode p : pages) if (p.path("path").asText().equals(path)) id = p.path("id").asInt();
    JsonNode page;
    if (id == null) {
      String q =
          "mutation($content:String!,$title:String!,$path:String!,$locale:String!){pages{create(content:$content,title:$title,path:$path,locale:$locale,description:\"Decision"
              + " source-linked"
              + " topic\",editor:\"markdown\",isPublished:true,isPrivate:true,tags:[]){responseResult{succeeded}"
              + " page{id path}}}}";
      JsonNode result =
          query(
                  q,
                  Json.obj(
                      "content",
                      text,
                      "title",
                      topic.path("title"),
                      "path",
                      path,
                      "locale",
                      c.wikiLocale))
              .path("pages")
              .path("create");
      if (!result.path("responseResult").path("succeeded").asBoolean())
        throw new ApiError(502, "WIKI_CREATE_FAILED", "Wiki 页面创建失败");
      page = result.path("page");
      id = page.path("id").asInt();
    } else {
      String q =
          "mutation($id:Int!,$content:String!,$title:String!){pages{update(id:$id,content:$content,title:$title){responseResult{succeeded}page{id"
              + " path}}}}";
      JsonNode result =
          query(q, Json.obj("id", id, "content", text, "title", topic.path("title")))
              .path("pages")
              .path("update");
      if (!result.path("responseResult").path("succeeded").asBoolean())
        throw new ApiError(502, "WIKI_UPDATE_FAILED", "Wiki 页面更新失败");
    }
    JsonNode actual =
        query("query($id:Int!){pages{single(id:$id){id content path locale}}}", Json.obj("id", id))
            .path("pages")
            .path("single");
    if (!actual.path("content").asText().equals(text) || !actual.path("path").asText().equals(path))
      throw new ApiError(502, "WIKI_READBACK_MISMATCH", "Wiki 回读与提交内容不一致");
    return new Result(id.toString(), publicUrl(path), Auth.hash(text));
  }
}
