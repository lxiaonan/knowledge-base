import MarkdownIt from "markdown-it";
import DOMPurify from "dompurify";
import { katex } from "@mdit/plugin-katex";
import hljs from "highlight.js/lib/core";
import java from "highlight.js/lib/languages/java";
import javascript from "highlight.js/lib/languages/javascript";
import typescript from "highlight.js/lib/languages/typescript";
import json from "highlight.js/lib/languages/json";
import python from "highlight.js/lib/languages/python";
import sql from "highlight.js/lib/languages/sql";
import bash from "highlight.js/lib/languages/bash";
import xml from "highlight.js/lib/languages/xml";
import css from "highlight.js/lib/languages/css";
import type Token from "markdown-it/lib/token.mjs";

for (const [name, language] of Object.entries({
  java,
  javascript,
  typescript,
  json,
  python,
  sql,
  bash,
  xml,
  css,
}))
  hljs.registerLanguage(name, language);
const aliases: Record<string, string> = {
  js: "javascript",
  ts: "typescript",
  sh: "bash",
  shell: "bash",
  html: "xml",
  vue: "xml",
};
interface RenderEnvironment {
  sourceNumbers: Set<number>;
  codeBlocks: string[];
}
export interface RenderedChatMarkdown {
  html: string;
  codeBlocks: string[];
}
const md = new MarkdownIt({
  html: false,
  breaks: false,
  linkify: true,
}).disable("image");
md.use(katex, {
  delimiters: "all",
  mathFence: true,
  throwOnError: false,
  trust: false,
  strict: "ignore",
  maxExpand: 500,
  maxSize: 20,
});
// A currency prefix such as "$100，" is not an opening math delimiter.
md.inline.ruler.before(
  "math_inline_dollar",
  "chat_currency",
  (state, silent) => {
    if (
      !/^\$\d+(?:[.,]\d+)*(?=[\s，。；、！？]|$)/.test(
        state.src.slice(state.pos),
      )
    )
      return false;
    const end = state.src.indexOf("$", state.pos + 1);
    if (end >= 0 && /[+*/^_=\\]/.test(state.src.slice(state.pos + 1, end)))
      return false;
    if (!silent) state.pending += "$";
    state.pos++;
    return true;
  },
);
const escape = md.utils.escapeHtml;

md.renderer.rules.fence = (
  tokens,
  index,
  options,
  environment: RenderEnvironment,
  self,
) => {
  const token = tokens[index]!;
  const label = token.info.trim().split(/\s+/)[0] || "text";
  if (label.toLowerCase() === "math" && md.renderer.rules.math_block)
    return md.renderer.rules.math_block(
      tokens,
      index,
      options,
      environment,
      self,
    );
  const language = aliases[label.toLowerCase()] || label.toLowerCase();
  const content = hljs.getLanguage(language)
    ? hljs.highlight(token.content, { language, ignoreIllegals: true }).value
    : escape(token.content);
  const codeIndex = environment.codeBlocks.push(token.content) - 1;
  return `<div class="md-code-block"><div class="md-code-toolbar"><span>${escape(label)}</span><button type="button" data-copy-code="${codeIndex}" aria-label="复制代码">复制代码</button></div><pre><code class="hljs">${content}</code></pre></div>`;
};
const linkOpen = md.renderer.rules.link_open;
md.renderer.rules.link_open = (tokens, index, options, env, self) => {
  tokens[index]!.attrSet("target", "_blank");
  tokens[index]!.attrSet("rel", "noopener noreferrer");
  return linkOpen
    ? linkOpen(tokens, index, options, env, self)
    : self.renderToken(tokens, index, options);
};
md.renderer.rules.table_open = () =>
  '<div class="md-table-scroll" tabindex="0" role="region" aria-label="表格，可横向滚动"><table>';
md.renderer.rules.table_close = () => "</table></div>\n";

md.core.ruler.after("inline", "chat_citations", (state) => {
  const env = state.env as RenderEnvironment;
  for (const block of state.tokens) {
    if (block.type !== "inline" || !block.children) continue;
    const children: Token[] = [];
    let linkDepth = 0;
    for (const token of block.children) {
      if (token.type === "link_open") linkDepth++;
      if (token.type !== "text" || linkDepth) children.push(token);
      else {
        let offset = 0;
        for (const match of token.content.matchAll(/\[(\d{1,3})\]/g)) {
          const number = Number(match[1]);
          if (!env.sourceNumbers.has(number)) continue;
          if (match.index > offset) {
            const text = new state.Token("text", "", 0);
            text.content = token.content.slice(offset, match.index);
            children.push(text);
          }
          const citation = new state.Token("html_inline", "", 0);
          citation.content = `<button type="button" class="md-citation" data-source-number="${number}" aria-label="查看引用来源 ${number}" title="查看来源 ${number}">${number}</button>`;
          children.push(citation);
          offset = match.index + match[0].length;
        }
        if (offset < token.content.length) {
          const text = new state.Token("text", "", 0);
          text.content = token.content.slice(offset);
          children.push(text);
        }
      }
      if (token.type === "link_close") linkDepth--;
    }
    block.children = children;
  }
});

export function renderChatMarkdown(
  content: string,
  sourceNumbers: number[] = [],
): RenderedChatMarkdown {
  const env: RenderEnvironment = {
    sourceNumbers: new Set(sourceNumbers),
    codeBlocks: [],
  };
  const html = DOMPurify.sanitize(
    md.render(content.replace(/\r\n?/g, "\n"), env),
    {
      USE_PROFILES: { html: true, svg: true, mathMl: true },
      ADD_TAGS: ["semantics", "annotation"],
      ADD_ATTR: ["target", "encoding"],
    },
  );
  return { html, codeBlocks: env.codeBlocks };
}
