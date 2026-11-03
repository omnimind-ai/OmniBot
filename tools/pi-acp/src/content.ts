import { resolve } from "node:path";
import { RequestError, type ContentBlock, type ToolCallContent, type ToolKind } from "@agentclientprotocol/sdk/unstable";
import type { ImageContent } from "@earendil-works/pi-ai";

export function promptContent(blocks: ContentBlock[]): { text: string; images: ImageContent[] } {
  const text: string[] = [];
  const images: ImageContent[] = [];
  for (const block of blocks) {
    switch (block.type) {
      case "text": text.push(block.text); break;
      case "image":
        images.push({ type: "image", data: block.data, mimeType: block.mimeType });
        break;
      case "resource_link":
        text.push(`Attached resource: ${block.uri}\n${block.name}${block.description ? `\n${block.description}` : ""}`);
        break;
      case "resource":
        if ("text" in block.resource) text.push(`Resource: ${block.resource.uri}\n${block.resource.text}`);
        else if (block.resource.mimeType?.startsWith("image/")) {
          images.push({ type: "image", data: block.resource.blob, mimeType: block.resource.mimeType });
        } else throw RequestError.invalidParams(undefined, "Binary attachments must be supplied as resource links.");
        break;
      default: throw RequestError.invalidParams(undefined, `Unsupported prompt content: ${block.type}`);
    }
  }
  return { text: text.join("\n\n"), images };
}

export function toolKind(name: string): ToolKind {
  if (["read", "ls"].includes(name)) return "read";
  if (["edit", "write"].includes(name)) return "edit";
  if (["bash", "powershell"].includes(name)) return "execute";
  if (["grep", "find"].includes(name)) return "search";
  return "other";
}

export function toolDescription(name: string, args: unknown, cwd: string) {
  const input = args && typeof args === "object" ? args as Record<string, unknown> : {};
  const path = typeof input.path === "string" ? input.path : undefined;
  const detail = path || (typeof input.command === "string" ? input.command : "");
  return {
    title: detail ? `${name}: ${detail}` : name,
    kind: toolKind(name),
    ...(path ? { locations: [{ path: resolve(cwd, path) }] } : {}),
  };
}

export function toolContent(result: unknown): ToolCallContent[] {
  if (!result || typeof result !== "object") return [];
  const content = (result as { content?: unknown }).content;
  if (!Array.isArray(content)) return [];
  return content.flatMap((block): ToolCallContent[] => {
    if (block?.type === "text" && typeof block.text === "string") {
      return [{ type: "content", content: { type: "text", text: block.text } }];
    }
    if (block?.type === "image" && typeof block.data === "string" && typeof block.mimeType === "string") {
      return [{ type: "content", content: { type: "image", data: block.data, mimeType: block.mimeType } }];
    }
    return [];
  });
}
