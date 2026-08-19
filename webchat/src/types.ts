export type ConnectionStatus = "connecting" | "online" | "offline";
export type MobileSection = "chat" | "workspace" | "browser";
export type ContextPanelName = "workspace" | "browser";
export type ConversationMode = "normal" | "codex" | "chat_only";

export interface ConversationCreateTarget {
  mode: ConversationMode;
  agentId?: string;
}

export interface AgentProfile {
  id: string;
  name: string;
  description?: string;
  enabled?: boolean;
  builtIn?: boolean;
}

export interface Conversation {
  id: number;
  mode?: string;
  agentId?: string;
  title?: string;
  summary?: string;
  lastMessage?: string;
  messageCount?: number;
  updatedAt?: number;
  isArchived?: boolean;
  isPinned?: boolean;
}

export interface Attachment {
  fileName: string;
  mimeType: string;
  size: number;
  dataUrl: string;
  isImage: boolean;
}

export interface ChatMessage {
  id?: number | string;
  contentId?: string;
  user?: number;
  type?: number;
  content?: unknown;
  streamMeta?: Record<string, unknown>;
  reasoning_content?: string;
  reasoningContent?: string;
  isError?: boolean;
  isLoading?: boolean;
  createAt?: number | string;
}

export interface WorkspaceInfo {
  rootPath?: string;
  [key: string]: unknown;
}

export interface WorkspaceItem {
  name: string;
  path: string;
  isDirectory: boolean;
  size?: number;
}

export interface WorkspaceListing {
  path?: string;
  items?: WorkspaceItem[];
}

export interface BrowserSnapshot {
  available?: boolean;
  title?: string;
  currentUrl?: string;
  [key: string]: unknown;
}

export interface BootstrapPayload {
  agentProfiles?: AgentProfile[];
  workspace?: {
    workspace?: WorkspaceInfo | null;
    root?: { path?: string } | null;
  } | null;
  browser?: BrowserSnapshot | null;
}

export interface RealtimeEventData {
  conversationId?: number;
  conversationMode?: string;
  mode?: string;
  messages?: ChatMessage[];
  snapshot?: BrowserSnapshot | null;
  kind?: string;
  taskId?: string;
  toolType?: string;
  [key: string]: unknown;
}

export type RealtimeEventName =
  | "conversation_created"
  | "conversation_updated"
  | "conversation_deleted"
  | "messages_replaced"
  | "acp_event"
  | "chat_task_event"
  | "browser_snapshot_updated"
  | "workspace_changed";

export interface RunResult {
  taskId?: string | number;
  turnId?: string;
  conversationMode?: ConversationMode;
  conversation?: Conversation;
}

export interface BrowserActionResult {
  snapshot?: BrowserSnapshot | null;
}

export interface WorkspaceFilePayload {
  content?: string;
}
