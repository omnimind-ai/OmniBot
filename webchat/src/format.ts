import { isAgentConversationMode } from "./conversationDraft";
import { isRecord } from "./api";
import type { ChatMessage, Conversation } from "./types";

export function escapeHtml(value: unknown): string {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

function inlineMarkdown(value: string): string {
  return escapeHtml(value)
    .replace(/`([^`\n]+)`/g, "<code>$1</code>")
    .replace(/\[([^\]\n]+)\]\(([^)\s"]+)\)/g, (match, text: string, url: string) => {
      if (!/^(https?:|mailto:)/i.test(url)) return text;
      return `<a href="${url}" target="_blank" rel="noreferrer noopener">${text}</a>`;
    })
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/(^|[^*\w])\*([^*\n]+)\*/g, "$1<em>$2</em>")
    .replace(/~~([^~\n]+)~~/g, "<del>$1</del>");
}

const HEADING_PATTERN = /^(#{1,6})\s+(.+)$/;
const HR_PATTERN = /^\s*(?:-{3,}|\*{3,}|_{3,})\s*$/;
const QUOTE_PATTERN = /^>\s?(.*)$/;
const LIST_PATTERN = /^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$/;
const TABLE_SEPARATOR_PATTERN = /^\|?[\s:|-]+\|[\s:|-]*$/;

function splitTableRow(line: string): string[] {
  const trimmed = line.trim().replace(/^\|/, "").replace(/\|$/, "");
  return trimmed.split("|").map((cell) => cell.trim());
}

function renderList(lines: string[], start: number): { html: string; next: number } {
  const first = LIST_PATTERN.exec(lines[start]);
  if (!first) return { html: "", next: start };
  const ordered = /^\d/.test(first[2]);
  const tag = ordered ? "ol" : "ul";
  const items: string[] = [];
  let next = start;
  while (next < lines.length) {
    const line = lines[next];
    const match = LIST_PATTERN.exec(line);
    if (!match || /^\d/.test(match[2]) !== ordered) break;
    items.push(`<li>${inlineMarkdown(match[3])}</li>`);
    next += 1;
  }
  return { html: `<${tag}>${items.join("")}</${tag}>`, next };
}

function renderTable(lines: string[], start: number): { html: string; next: number } {
  const headers = splitTableRow(lines[start]);
  let next = start + 2;
  const rows: string[][] = [];
  while (next < lines.length && lines[next].includes("|") && lines[next].trim()) {
    if (TABLE_SEPARATOR_PATTERN.test(lines[next])) break;
    rows.push(splitTableRow(lines[next]));
    next += 1;
  }
  const head = `<thead><tr>${headers.map((cell) => `<th>${inlineMarkdown(cell)}</th>`).join("")}</tr></thead>`;
  const body = rows.length
    ? `<tbody>${rows.map((row) => `<tr>${row.map((cell) => `<td>${inlineMarkdown(cell)}</td>`).join("")}</tr>`).join("")}</tbody>`
    : "";
  return { html: `<table>${head}${body}</table>`, next };
}

function renderBlocks(segment: string): string {
  const lines = segment.split("\n");
  const html: string[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index];
    if (!line.trim()) {
      index += 1;
      continue;
    }
    const heading = HEADING_PATTERN.exec(line);
    if (heading) {
      const level = heading[1].length;
      html.push(`<h${level}>${inlineMarkdown(heading[2].trim())}</h${level}>`);
      index += 1;
      continue;
    }
    if (HR_PATTERN.test(line)) {
      html.push("<hr>");
      index += 1;
      continue;
    }
    if (QUOTE_PATTERN.test(line)) {
      const quoted: string[] = [];
      while (index < lines.length && QUOTE_PATTERN.test(lines[index])) {
        quoted.push(QUOTE_PATTERN.exec(lines[index])?.[1] ?? "");
        index += 1;
      }
      html.push(`<blockquote><p>${inlineMarkdown(quoted.join("\n")).replaceAll("\n", "<br>")}</p></blockquote>`);
      continue;
    }
    if (line.trimStart().startsWith("|") && index + 1 < lines.length && TABLE_SEPARATOR_PATTERN.test(lines[index + 1])) {
      const table = renderTable(lines, index);
      html.push(table.html);
      index = table.next;
      continue;
    }
    if (LIST_PATTERN.test(line)) {
      const list = renderList(lines, index);
      html.push(list.html);
      index = list.next;
      continue;
    }
    const paragraph: string[] = [];
    while (
      index < lines.length
      && lines[index].trim()
      && !HEADING_PATTERN.test(lines[index])
      && !HR_PATTERN.test(lines[index])
      && !QUOTE_PATTERN.test(lines[index])
      && !LIST_PATTERN.test(lines[index])
      && !(lines[index].trimStart().startsWith("|") && index + 1 < lines.length && TABLE_SEPARATOR_PATTERN.test(lines[index + 1]))
    ) {
      paragraph.push(lines[index]);
      index += 1;
    }
    if (paragraph.length) {
      html.push(`<p>${inlineMarkdown(paragraph.join("\n")).replaceAll("\n", "<br>")}</p>`);
    }
  }
  return html.join("");
}

export function markdownToHtml(value: unknown): string {
  const source = String(value ?? "").replace(/\r\n?/g, "\n").trim();
  if (!source) return "";
  return source.split(/```/).map((block, index) => {
    if (index % 2 === 1) {
      const firstBreak = block.indexOf("\n");
      const code = firstBreak >= 0 ? block.slice(firstBreak + 1) : block;
      return `<pre><code>${escapeHtml(code.trimEnd())}</code></pre>`;
    }
    return renderBlocks(block);
  }).join("");
}

export function modeLabel(mode?: string, agentId?: string): string {
  if (isAgentConversationMode(mode)) {
    return ({
      "codex-acp": "Codex",
      "claude-code-acp": "Claude Code",
      "opencode-acp": "OpenCode",
      "pi-acp": "Pi",
      "deepseek-harness-acp": "DeepSeek Harness",
    } as Record<string, string>)[agentId ?? ""] ?? "Agent";
  }
  return ({
    normal: "小万",
    chat_only: "纯聊天",
    openclaw: "OpenClaw",
    subagent: "SubAgent",
  } as Record<string, string>)[mode ?? "normal"] ?? "普通";
}

export function relativeDate(raw?: number): string {
  const value = Number(raw);
  if (!Number.isFinite(value) || value <= 0) return "";
  const date = new Date(value);
  const diff = Date.now() - value;
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时`;
  return `${date.getMonth() + 1}-${date.getDate()}`;
}

export function conversationKey(conversation: Conversation | null | undefined): string {
  return `${conversation?.mode ?? "normal"}:${Number(conversation?.id ?? 0)}`;
}

export function messageTime(message: ChatMessage): number {
  const raw = message.createAt;
  const date = typeof raw === "number" ? new Date(raw) : new Date(String(raw ?? ""));
  return Number.isNaN(date.getTime()) ? 0 : date.getTime();
}

export function formatBytes(raw?: number): string {
  const value = Number(raw ?? 0);
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${Math.round(value / 1024)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

export function messageContent(message: ChatMessage): Record<string, unknown> {
  return isRecord(message.content) ? message.content : {};
}
