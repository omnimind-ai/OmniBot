import { randomUUID } from "node:crypto";
import type { SessionNotification } from "@agentclientprotocol/sdk/unstable";
import type { AgentMessage } from "@earendil-works/pi-agent-core";
import type { AgentSessionEvent, SessionManager } from "@earendil-works/pi-coding-agent";
import { toolContent, toolDescription } from "./content.js";

type Update = SessionNotification["update"];
export type Publish = (update: Update) => void;
const MESSAGE_ID_ENTRY = "omnibot.acp.message";

/** Translate Pi events at the ACP boundary. This does not own prompt completion. */
export class PiProjection {
  private messageId?: string;
  private streamed = new Map<number, string>();
  private tools = new Set<string>();

  constructor(private manager: SessionManager, private cwd: string, private publish: Publish) {}

  event(event: AgentSessionEvent): void {
    switch (event.type) {
      case "message_start": {
        const role = event.message.role;
        if (role !== "user" && role !== "assistant") break;
        this.messageId = `pi:${randomUUID()}`;
        this.streamed.clear();
        // Pi appends the finalized message after message_end listeners return.
        // Store its ACP identity before streaming. Replay associates this marker
        // with the following Pi message entry, including after a process restart.
        // Custom entries are Pi's supported metadata surface, outside model context.
        this.manager.appendCustomEntry(MESSAGE_ID_ENTRY, { messageId: this.messageId, role });
        break;
      }
      case "message_update": {
        const delta = event.assistantMessageEvent;
        if (delta.type !== "text_delta" && delta.type !== "thinking_delta") break;
        this.streamed.set(delta.contentIndex, (this.streamed.get(delta.contentIndex) || "") + delta.delta);
        this.chunk(delta.type === "thinking_delta" ? "agent_thought_chunk" : "agent_message_chunk", delta.delta);
        break;
      }
      case "message_end":
        if (event.message.role === "user") this.user(event.message, this.messageId);
        else if (event.message.role === "assistant") {
          event.message.content.forEach((block, index) => {
            if (block.type !== "text" && block.type !== "thinking") return;
            const text = block.type === "text" ? block.text : block.thinking;
            const sent = this.streamed.get(index) || "";
            if (text.startsWith(sent) && text.length > sent.length) {
              this.chunk(block.type === "text" ? "agent_message_chunk" : "agent_thought_chunk", text.slice(sent.length));
            }
          });
        }
        break;
      case "tool_execution_start":
        this.tools.add(event.toolCallId);
        this.publish({
          sessionUpdate: "tool_call", toolCallId: event.toolCallId,
          ...toolDescription(event.toolName, event.args, this.cwd),
          status: "in_progress", rawInput: event.args,
        });
        break;
      case "tool_execution_update":
        if (this.tools.has(event.toolCallId)) this.publish({
          sessionUpdate: "tool_call_update", toolCallId: event.toolCallId,
          status: "in_progress", content: toolContent(event.partialResult),
        });
        break;
      case "tool_execution_end":
        if (this.tools.delete(event.toolCallId)) this.publish({
          sessionUpdate: "tool_call_update", toolCallId: event.toolCallId,
          status: event.isError ? "failed" : "completed", content: toolContent(event.result),
          rawOutput: event.result,
        });
        break;
      // agent_end, turn_end and compaction are Pi internals. Only the owning
      // ACP PromptResponse (or JSON-RPC error) terminates the host prompt.
    }
  }

  replay(): void {
    let pending: { messageId: string; role: string } | undefined;
    for (const entry of this.manager.getBranch()) {
      if (entry.type === "custom" && entry.customType === MESSAGE_ID_ENTRY) {
        const data = entry.data as Partial<{ messageId: string; role: string }> | undefined;
        if (typeof data?.messageId === "string" && typeof data.role === "string") {
          pending = { messageId: data.messageId, role: data.role };
        }
        continue;
      }
      if (entry.type !== "message") continue;
      const message = entry.message;
      const id = pending?.role === message.role ? pending.messageId : `pi:${entry.id}`;
      if (message.role === "user" || message.role === "assistant") pending = undefined;
      if (message.role === "user") this.user(message, id);
      else if (message.role === "assistant") {
        for (const block of message.content) {
          if (block.type === "text" || block.type === "thinking") {
            this.publish({
              sessionUpdate: block.type === "text" ? "agent_message_chunk" : "agent_thought_chunk",
              messageId: id,
              content: { type: "text", text: block.type === "text" ? block.text : block.thinking },
            });
          } else if (block.type === "toolCall") {
            this.publish({
              sessionUpdate: "tool_call", toolCallId: block.id,
              ...toolDescription(block.name, block.arguments, this.cwd),
              rawInput: block.arguments, status: "pending",
            });
          }
        }
      } else if (message.role === "toolResult") {
        this.publish({
          sessionUpdate: "tool_call_update", toolCallId: message.toolCallId,
          status: message.isError ? "failed" : "completed",
          content: toolContent(message), rawOutput: message,
        });
      }
    }
  }

  private chunk(sessionUpdate: "agent_message_chunk" | "agent_thought_chunk", text: string): void {
    if (text) this.publish({ sessionUpdate, messageId: this.messageId, content: { type: "text", text } });
  }

  private user(message: AgentMessage & { role: "user" }, messageId?: string): void {
    const content = typeof message.content === "string" ? [{ type: "text" as const, text: message.content }] : message.content;
    for (const block of content) {
      if (block.type === "text" || block.type === "image") {
        this.publish({ sessionUpdate: "user_message_chunk", messageId, content: block });
      }
    }
  }
}
