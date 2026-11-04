import { describe, expect, it } from "vitest";
import { renderMarkdown } from "../src/markdown";

describe("untrusted assistant Markdown", () => {
  it("renders Chinese paragraphs, headings, lists and code without losing text", () => {
    const html = renderMarkdown(
      "## 活动小结\n\n共 **20 人**。\n下一行。\n\n- 交换 35 本\n- 剩余待确认\n\n```html\n<script>alert(1)</script>\n```",
    );
    expect(html).toContain("<h2>活动小结</h2>");
    expect(html).toContain("<strong>20 人</strong>");
    expect(html).toContain("<br>");
    expect(html).toContain("<li>交换 35 本</li>");
    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain("<script>");
  });
  it("escapes HTML and never embeds external images or SVG", () => {
    const html = renderMarkdown(
      "<img src=x onerror=alert(1)>\n<svg onload=alert(1)></svg>\n![追踪](https://example.com/pixel)",
    );
    expect(html).not.toMatch(/<(img|svg|script)\b/);
    expect(html).toContain("&lt;img");
  });
  it.each([
    "javascript:alert(1)",
    "jav&#x61;script:alert(1)",
    "data:text/html,test",
    "file:///etc/passwd",
    "//evil.example",
    "/api/v1/logout",
    "https://user:password@example.com",
  ])("does not create a dangerous or relative link: %s", (url) => {
    expect(renderMarkdown(`[点我](${url})`)).not.toContain("<a ");
  });
  it("allows explicit HTTP links with safe new-tab attributes", () => {
    expect(
      renderMarkdown("[原文](https://example.com/path?q=1&x=2)"),
    ).toContain(
      'href="https://example.com/path?q=1&amp;x=2" target="_blank" rel="noopener noreferrer"',
    );
  });
});
