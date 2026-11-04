import MarkdownIt from "markdown-it";

// Model text is untrusted. No raw HTML, embedded media, or automatic requests.
const markdown = new MarkdownIt({ html: false, breaks: true, linkify: false });
markdown.disable("image");
markdown.validateLink = (value: string) => {
  try {
    const url = new URL(value);
    return (
      ["https:", "http:"].includes(url.protocol) &&
      !url.username &&
      !url.password
    );
  } catch {
    return false;
  }
};
markdown.renderer.rules.link_open = (tokens, index, options, _env, self) => {
  tokens[index].attrSet("target", "_blank");
  tokens[index].attrSet("rel", "noopener noreferrer");
  return self.renderToken(tokens, index, options);
};

export const renderMarkdown = (content: string) => markdown.render(content);
