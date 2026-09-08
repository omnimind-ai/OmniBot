import { useEffect, useRef, useState } from "react";
import { request } from "./api";
import { ChatPanel } from "./components/ChatPanel";
import { ContextPane } from "./components/ContextPane";
import { ConversationSidebar } from "./components/ConversationSidebar";
import { Icon } from "./components/Icon";
import { LoginView } from "./components/LoginView";
import {
  createConversationDraft,
  isPersistedConversation,
} from "./conversationDraft";
import { appendConversationNavigation } from "./conversationNavigation";
import { conversationKey } from "./format";
import { useRealtime } from "./hooks/useRealtime";
import { reconcileCodexMessages } from "./messageReconciliation";
import type {
  Attachment,
  AgentProfile,
  BootstrapPayload,
  BrowserActionResult,
  BrowserSnapshot,
  ChatMessage,
  ContextPanelName,
  Conversation,
  ConversationCreateTarget,
  ConversationMode,
  MobileSection,
  RealtimeEventData,
  RealtimeEventName,
  RunResult,
  WorkspaceFilePayload,
  WorkspaceInfo,
  WorkspaceItem,
  WorkspaceListing,
} from "./types";

const TOKEN_STORAGE_KEY = "omnibot_webchat_token";
const MOBILE_SECTION_ICON = {
  chat: "agent",
  workspace: "workspace",
  browser: "browser",
} as const;
const CONVERSATION_MODES = new Set<ConversationMode>(["normal", "codex", "chat_only"]);

function normalizeConversationMode(mode: string | undefined): ConversationMode {
  return CONVERSATION_MODES.has(mode as ConversationMode)
    ? mode as ConversationMode
    : "normal";
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : String(error ?? "请求失败");
}

function initialToken(): string {
  const queryToken = new URLSearchParams(window.location.search).get("token")?.trim();
  return queryToken || localStorage.getItem(TOKEN_STORAGE_KEY)?.trim() || "";
}

function createTaskId(): string {
  if (typeof crypto.randomUUID === "function") return crypto.randomUUID();
  return `web-${Date.now()}-${Math.random().toString(36).slice(2, 12)}`;
}

export default function App() {
  const [authenticated, setAuthenticated] = useState(false);
  const [authenticating, setAuthenticating] = useState(false);
  const [loginError, setLoginError] = useState("");
  const [globalError, setGlobalError] = useState("");
  const [conversations, setConversations] = useState<Conversation[]>([]);
  const [agentProfiles, setAgentProfiles] = useState<AgentProfile[]>([]);
  const [archivedConversations, setArchivedConversations] = useState<Conversation[]>([]);
  const [archivedLoading, setArchivedLoading] = useState(false);
  const [selectedConversation, setSelectedConversation] = useState<Conversation | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [sending, setSending] = useState(false);
  const [activeTaskId, setActiveTaskId] = useState<string | null>(null);
  const [activeRenderTaskId, setActiveRenderTaskId] = useState<string | null>(null);
  const [clarifyTaskId, setClarifyTaskId] = useState<string | null>(null);
  const [workspaceInfo, setWorkspaceInfo] = useState<WorkspaceInfo | null>(null);
  const [workspacePath, setWorkspacePath] = useState("");
  const [workspaceItems, setWorkspaceItems] = useState<WorkspaceItem[]>([]);
  const [workspaceFilePath, setWorkspaceFilePath] = useState<string | null>(null);
  const [workspaceContent, setWorkspaceContent] = useState("");
  const [workspaceDirty, setWorkspaceDirty] = useState(false);
  const [browserSnapshot, setBrowserSnapshot] = useState<BrowserSnapshot | null>(null);
  const [browserFrameSeed, setBrowserFrameSeed] = useState(0);
  const [contextPanel, setContextPanel] = useState<ContextPanelName>("workspace");
  const [mobileSection, setMobileSectionState] = useState<MobileSection>("chat");
  const [conversationsOpen, setConversationsOpen] = useState(false);
  const [leftSidebarCollapsed, setLeftSidebarCollapsed] = useState(false);
  const [rightSidebarCollapsed, setRightSidebarCollapsed] = useState(false);
  const [toast, setToast] = useState("");
  const selectedRef = useRef<Conversation | null>(null);
  const conversationHistoryRef = useRef<string[]>([]);
  const conversationHistoryIndexRef = useRef(-1);
  const completedTaskIdsRef = useRef(new Set<string>());
  const workspacePathRef = useRef("");
  const toastTimerRef = useRef<number | null>(null);
  const autoLoginToken = useRef(initialToken());

  function showError(error: unknown) {
    setGlobalError(errorMessage(error));
  }

  function showToast(message: string) {
    if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    setToast(message);
    toastTimerRef.current = window.setTimeout(() => setToast(""), 2600);
  }

  function applyConversationSnapshot(conversation: Conversation) {
    if (!isPersistedConversation(conversation)) return;
    setConversations((current) => [
      conversation,
      ...current.filter((item) => Number(item.id) !== Number(conversation.id)),
    ].sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0)));
    if (Number(selectedRef.current?.id ?? 0) === Number(conversation.id)) {
      selectedRef.current = conversation;
      setSelectedConversation(conversation);
    }
  }

  function recordConversationNavigation(conversation: Conversation | null) {
    if (!conversation || Number(conversation.id ?? 0) <= 0) return;
    const key = conversationKey(conversation);
    const history = conversationHistoryRef.current;
    const index = conversationHistoryIndexRef.current;
    if (history[index] === key) return;
    const next = appendConversationNavigation({ keys: history, index }, key);
    conversationHistoryRef.current = next.keys;
    conversationHistoryIndexRef.current = next.index;
  }

  function navigationTarget(direction: -1 | 1) {
    const conversationsByKey = new Map(
      conversations.map((conversation) => [conversationKey(conversation), conversation]),
    );
    const history = conversationHistoryRef.current;
    for (
      let index = conversationHistoryIndexRef.current + direction;
      index >= 0 && index < history.length;
      index += direction
    ) {
      const conversation = conversationsByKey.get(history[index]);
      if (conversation) return { conversation, index };
    }
    return null;
  }

  async function navigateConversationHistory(direction: -1 | 1) {
    const target = navigationTarget(direction);
    if (!target) return;
    conversationHistoryIndexRef.current = target.index;
    selectedRef.current = target.conversation;
    setSelectedConversation(target.conversation);
    await loadMessages(target.conversation);
  }

  async function loadMessages(conversation = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    try {
      const payload = await request<ChatMessage[]>(`/conversations/${conversation.id}/messages`, {
        query: { mode: conversation.mode ?? "normal" },
      });
      if (conversationKey(conversation) === conversationKey(selectedRef.current)) {
        const incoming = Array.isArray(payload) ? payload : [];
        setMessages((current) => (
          normalizeConversationMode(conversation.mode) === "codex"
            ? reconcileCodexMessages(current, incoming)
            : incoming
        ));
      }
    } catch (error) {
      showError(error);
    }
  }

  async function loadConversations(preserveSelection = true) {
    const payload = await request<Conversation[]>("/conversations", {
      query: { includeArchived: false },
    });
    const previousKey = preserveSelection ? conversationKey(selectedRef.current) : null;
    const nextConversations = (Array.isArray(payload) ? payload : [])
      .filter((item) => !item.isArchived)
      .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0));
    const currentSelection = selectedRef.current;
    if (
      preserveSelection
      && currentSelection
      && !isPersistedConversation(currentSelection)
    ) {
      setConversations(nextConversations);
      setSelectedConversation(currentSelection);
      return;
    }
    const nextSelected = nextConversations.find((item) => conversationKey(item) === previousKey)
      ?? nextConversations[0]
      ?? null;
    const selectionChanged = conversationKey(nextSelected) !== previousKey;
    setConversations(nextConversations);
    selectedRef.current = nextSelected;
    setSelectedConversation(nextSelected);
    recordConversationNavigation(nextSelected);
    if (nextSelected) {
      if (selectionChanged) setMessages([]);
      await loadMessages(nextSelected);
    }
    else setMessages([]);
  }

  async function loadArchivedConversations(reportError = true) {
    setArchivedLoading(true);
    try {
      const payload = await request<Conversation[]>("/conversations", {
        query: { includeArchived: true, archivedOnly: true },
      });
      setArchivedConversations(
        (Array.isArray(payload) ? payload : [])
          .filter((item) => item.isArchived)
          .sort((left, right) => Number(right.updatedAt ?? 0) - Number(left.updatedAt ?? 0)),
      );
    } catch (error) {
      if (reportError) showError(error);
    } finally {
      setArchivedLoading(false);
    }
  }

  async function loadWorkspace(path = workspacePathRef.current, reportError = true) {
    if (!path) return;
    try {
      const payload = await request<WorkspaceListing>("/workspaces", { query: { path } });
      const nextPath = String(payload?.path ?? path);
      workspacePathRef.current = nextPath;
      setWorkspacePath(nextPath);
      setWorkspaceItems(Array.isArray(payload?.items) ? payload.items : []);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function authenticate(token: string) {
    setLoginError("");
    setAuthenticating(true);
    try {
      await request("/session/bootstrap", { method: "POST", body: { token } });
      localStorage.setItem(TOKEN_STORAGE_KEY, token);
      const bootstrap = await request<BootstrapPayload>("/bootstrap");
      const info = bootstrap?.workspace?.workspace ?? null;
      const rootPath = bootstrap?.workspace?.root?.path ?? info?.rootPath ?? "";
      setWorkspaceInfo(info);
      workspacePathRef.current = rootPath;
      setWorkspacePath(rootPath);
      setBrowserSnapshot(bootstrap?.browser ?? null);
      setAgentProfiles(
        Array.isArray(bootstrap?.agentProfiles) ? bootstrap.agentProfiles : [],
      );
      setAuthenticated(true);

      const url = new URL(window.location.href);
      if (url.searchParams.has("token")) {
        url.searchParams.delete("token");
        window.history.replaceState(null, "", `${url.pathname}${url.search}${url.hash}`);
      }
      await Promise.all([
        loadConversations(false),
        rootPath ? loadWorkspace(rootPath, false) : Promise.resolve(),
      ]);
    } catch (error) {
      localStorage.removeItem(TOKEN_STORAGE_KEY);
      setLoginError(errorMessage(error));
      setAuthenticated(false);
    } finally {
      setAuthenticating(false);
    }
  }

  function createConversation(target: ConversationCreateTarget) {
    setGlobalError("");
    const draftConversation = createConversationDraft(
      target.mode,
      Date.now(),
      target.agentId,
    );
    selectedRef.current = draftConversation;
    setSelectedConversation(draftConversation);
    setMessages([]);
    setActiveTaskId(null);
    setActiveRenderTaskId(null);
    setClarifyTaskId(null);
    setConversationsOpen(false);
  }

  async function selectConversation(conversation: Conversation) {
    selectedRef.current = conversation;
    setSelectedConversation(conversation);
    setMessages([]);
    recordConversationNavigation(conversation);
    setConversationsOpen(false);
    await loadMessages(conversation);
  }

  async function updateArchiveState(
    conversation: Conversation | null = selectedRef.current,
    nextArchived?: boolean,
  ) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    const archived = nextArchived ?? !conversation.isArchived;
    try {
      await request(`/conversations/${conversation.id}`, {
        method: "PATCH",
        body: { isArchived: archived },
      });
      await Promise.all([
        loadConversations(true),
        loadArchivedConversations(false),
      ]);
      showToast(archived ? "已归档" : "已恢复到会话列表");
    } catch (error) {
      showError(error);
    }
  }

  async function updatePinState(conversation: Conversation, pinned: boolean) {
    if (!isPersistedConversation(conversation)) return;
    try {
      await request(`/conversations/${conversation.id}`, {
        method: "PATCH",
        body: { isPinned: pinned },
      });
      await loadConversations(true);
      showToast(pinned ? "已置顶" : "已取消置顶");
    } catch (error) {
      showError(error);
    }
  }

  async function deleteConversation(conversation: Conversation | null = selectedRef.current) {
    if (!conversation || !isPersistedConversation(conversation)) return;
    if (!window.confirm(`删除“${conversation.title || "当前对话"}”？此操作无法撤销。`)) return;
    try {
      await request(`/conversations/${conversation.id}`, { method: "DELETE" });
      await Promise.all([
        loadConversations(true),
        loadArchivedConversations(false),
      ]);
      showToast("会话已删除");
    } catch (error) {
      showError(error);
    }
  }

  async function sendMessage(text: string, attachments: Attachment[]): Promise<boolean> {
    setGlobalError("");
    setSending(true);
    let conversationCreatedForSend: Conversation | null = null;
    let optimisticUserEntryId: string | null = null;
    try {
      if (clarifyTaskId) {
        await request(`/tasks/${encodeURIComponent(clarifyTaskId)}/clarify`, {
          method: "POST",
          body: { reply: text },
        });
        setClarifyTaskId(null);
        return true;
      }

      let conversation = selectedRef.current;
      if (!isPersistedConversation(conversation)) {
        const draftMode = normalizeConversationMode(conversation?.mode);
        const draftAgentId = conversation?.agentId?.trim() || undefined;
        conversation = await request<Conversation>("/conversations", {
          method: "POST",
          body: {
            title: "新对话",
            mode: draftMode,
            agentId: draftAgentId,
          },
        });
        conversation = {
          ...conversation,
          mode: draftMode,
          agentId: conversation.agentId ?? draftAgentId,
        };
        conversationCreatedForSend = conversation;
        selectedRef.current = conversation;
        setSelectedConversation(conversation);
        applyConversationSnapshot(conversation);
        recordConversationNavigation(conversation);
      }
      if (!conversation) throw new Error("无法创建新对话");
      const conversationMode = normalizeConversationMode(conversation.mode);
      const taskId = createTaskId();
      const userMessageCreatedAt = Date.now();
      optimisticUserEntryId = `${taskId}-user`;
      const optimisticUserMessage: ChatMessage = {
        id: optimisticUserEntryId,
        type: 1,
        user: 1,
        content: {
          id: optimisticUserEntryId,
          text,
          ...(attachments.length ? { attachments } : {}),
        },
        createAt: userMessageCreatedAt,
      };
      completedTaskIdsRef.current.delete(taskId);
      setActiveTaskId(taskId);
      setActiveRenderTaskId(taskId);
      setMessages((current) => (
        conversationMode === "codex"
          ? reconcileCodexMessages(current, [optimisticUserMessage])
          : [...current, optimisticUserMessage]
      ));
      const result = await request<RunResult>(`/conversations/${conversation.id}/runs`, {
        method: "POST",
        body: {
          taskId,
          userMessage: text,
          userMessageCreatedAt,
          conversationMode,
          agentId: conversation.agentId,
          attachments,
        },
      });
      if (result?.conversation) {
        const updatedConversation = {
          ...result.conversation,
          mode: result.conversationMode ?? conversationMode,
          agentId: result.conversation.agentId ?? conversation.agentId,
        };
        selectedRef.current = updatedConversation;
        setSelectedConversation(updatedConversation);
        applyConversationSnapshot(updatedConversation);
      }
      const acceptedTaskId = String(result?.taskId ?? taskId);
      const completedBeforeAcceptance = completedTaskIdsRef.current.has(acceptedTaskId);
      setActiveTaskId(completedBeforeAcceptance ? null : acceptedTaskId);
      setActiveRenderTaskId(
        completedBeforeAcceptance
          ? null
          : String(result?.turnId ?? acceptedTaskId),
      );
      completedTaskIdsRef.current.delete(acceptedTaskId);
      void loadConversations(true).catch(showError);
      return true;
    } catch (error) {
      showError(error);
      setActiveTaskId(null);
      setActiveRenderTaskId(null);
      if (optimisticUserEntryId) {
        const failedEntryId = optimisticUserEntryId;
        setMessages((current) => current.filter((message) => (
          String(message.id ?? message.contentId ?? "") !== failedEntryId
        )));
      }
      if (conversationCreatedForSend) {
        const createdConversation = conversationCreatedForSend;
        try {
          const persistedMessages = await request<ChatMessage[]>(
            `/conversations/${createdConversation.id}/messages`,
            { query: { mode: createdConversation.mode ?? "normal" } },
          );
          if (!persistedMessages.length) {
            await request(`/conversations/${createdConversation.id}`, { method: "DELETE" });
            if (
              Number(selectedRef.current?.id ?? 0) ===
              Number(createdConversation.id)
            ) {
              const draft = createConversationDraft(
                normalizeConversationMode(createdConversation.mode),
                Date.now(),
                createdConversation.agentId,
              );
              selectedRef.current = draft;
              setSelectedConversation(draft);
              setMessages([]);
            }
          }
        } catch {
          // Keep the conversation when its persisted state cannot be confirmed.
        }
      }
      const current = selectedRef.current;
      if (isPersistedConversation(current)) {
        void loadMessages(current);
      }
      return false;
    } finally {
      setSending(false);
    }
  }

  async function cancelRun() {
    if (!activeTaskId) return;
    try {
      await request(`/tasks/${encodeURIComponent(activeTaskId)}/cancel`, { method: "POST" });
      setActiveTaskId(null);
      setActiveRenderTaskId(null);
    } catch (error) {
      showError(error);
    }
  }

  async function openWorkspaceFile(path: string) {
    try {
      const payload = await request<WorkspaceFilePayload>("/workspaces/file", {
        query: { path },
      });
      setWorkspaceFilePath(path);
      setWorkspaceContent(String(payload?.content ?? ""));
      setWorkspaceDirty(false);
    } catch (error) {
      showError(error);
    }
  }

  function workspaceParentPath(): string {
    const root = String(workspaceInfo?.rootPath ?? "").replace(/\/$/, "");
    const current = String(workspacePathRef.current).replace(/\/$/, "");
    if (!current || current === root) return current;
    const index = current.lastIndexOf("/");
    const parent = index > 0 ? current.slice(0, index) : "/";
    return root && !parent.startsWith(root) ? root : parent;
  }

  async function saveWorkspaceFile() {
    if (!workspaceFilePath || !workspaceDirty) return;
    try {
      await request("/workspaces/file", {
        method: "PUT",
        body: { path: workspaceFilePath, content: workspaceContent, append: false },
      });
      setWorkspaceDirty(false);
      showToast("文件已保存");
    } catch (error) {
      showError(error);
    }
  }

  async function refreshBrowser(reportError = true) {
    try {
      setBrowserSnapshot(await request<BrowserSnapshot>("/browser/snapshot"));
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      if (reportError) showError(error);
    }
  }

  async function browserAction(payload: Record<string, unknown>) {
    try {
      const result = await request<BrowserActionResult>("/browser/action", { method: "POST", body: payload });
      if (result?.snapshot !== undefined) setBrowserSnapshot(result.snapshot);
      setBrowserFrameSeed((seed) => seed + 1);
    } catch (error) {
      showError(error);
    }
  }

  function sameSelectedConversation(data: RealtimeEventData): boolean {
    const selected = selectedRef.current;
    return Boolean(
      selected
      && Number(data.conversationId ?? 0) === Number(selected.id)
      && String(data.conversationMode ?? data.mode ?? "normal") === String(selected.mode ?? "normal"),
    );
  }

  function handleRealtimeEvent(eventName: RealtimeEventName, data: RealtimeEventData) {
    if (["conversation_created", "conversation_updated", "conversation_deleted"].includes(eventName)) {
      void loadConversations(true).catch(showError);
      void loadArchivedConversations(false);
      return;
    }
    if (eventName === "messages_replaced" && sameSelectedConversation(data)) {
      const incoming = Array.isArray(data.messages) ? data.messages : [];
      const mode = normalizeConversationMode(
        String(data.conversationMode ?? data.mode ?? selectedRef.current?.mode ?? "normal"),
      );
      setMessages((current) => (
        mode === "codex"
          ? reconcileCodexMessages(current, incoming)
          : incoming
      ));
      return;
    }
    if (eventName === "workspace_changed" && workspacePathRef.current) {
      void loadWorkspace(workspacePathRef.current, false);
      return;
    }
    if (eventName === "browser_snapshot_updated") {
      setBrowserSnapshot(data.snapshot ?? null);
      setBrowserFrameSeed((seed) => seed + 1);
      return;
    }
    if (eventName === "chat_task_event") {
      const kind = String(data.kind ?? "");
      const taskId = String(data.taskId ?? "");
      if (["completed", "error"].includes(kind)) {
        if (taskId) completedTaskIdsRef.current.add(taskId);
        setActiveTaskId(null);
        setActiveRenderTaskId(null);
        setClarifyTaskId(null);
      }
      return;
    }
    if (eventName !== "acp_event") return;
    const presentation = data.presentation && typeof data.presentation === "object"
      ? data.presentation as Record<string, unknown>
      : null;
    const kind = String(presentation?.kind ?? "");
    const turnId = String(data.turnId ?? presentation?.turnId ?? "");
    if (kind === "approval_requested") setClarifyTaskId(turnId || activeTaskId);
    if (["turn_completed", "turn_failed"].includes(kind)) {
      if (turnId) completedTaskIdsRef.current.add(turnId);
      setActiveTaskId(null);
      setActiveRenderTaskId(null);
      setClarifyTaskId(null);
    }
    if (kind === "tool_completed" && String(data.toolType ?? "") === "browser") {
      void refreshBrowser(false);
    }
  }

  const connectionStatus = useRealtime(authenticated, handleRealtimeEvent);

  function selectMobileSection(section: MobileSection) {
    setMobileSectionState(section);
    if (section !== "chat") setContextPanel(section);
  }

  useEffect(() => {
    const token = autoLoginToken.current;
    if (token) void authenticate(token);
    return () => {
      if (toastTimerRef.current !== null) window.clearTimeout(toastTimerRef.current);
    };
  }, []);

  useEffect(() => {
    const guard = (event: BeforeUnloadEvent) => {
      if (!workspaceDirty) return;
      event.preventDefault();
    };
    window.addEventListener("beforeunload", guard);
    return () => window.removeEventListener("beforeunload", guard);
  }, [workspaceDirty]);

  if (!authenticated) {
    return (
      <LoginView
        initialToken={autoLoginToken.current}
        busy={authenticating}
        error={loginError}
        onLogin={authenticate}
      />
    );
  }

  const previousConversation = navigationTarget(-1);
  const nextConversation = navigationTarget(1);

  return (
    <>
      <div
        className={[
          "app-view",
          conversationsOpen && "conversations-open",
          leftSidebarCollapsed && "left-sidebar-collapsed",
          rightSidebarCollapsed && "right-sidebar-collapsed",
        ].filter(Boolean).join(" ")}
        data-mobile-section={mobileSection}
      >
        <header className="desktop-navigation-bar">
          <nav className="desktop-navigation-group" aria-label="对话导航">
            <button
              className="topbar-icon"
              type="button"
              aria-label={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              title={leftSidebarCollapsed ? "展开左侧边栏" : "收起左侧边栏"}
              aria-pressed={!leftSidebarCollapsed}
              onClick={() => setLeftSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="回退到上一会话"
              title="回退到上一会话"
              disabled={!previousConversation}
              onClick={() => void navigateConversationHistory(-1)}
            >
              <Icon name="arrow-left" size={18} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label="前进到下一会话"
              title="前进到下一会话"
              disabled={!nextConversation}
              onClick={() => void navigateConversationHistory(1)}
            >
              <Icon name="arrow-right" size={18} />
            </button>
          </nav>
          <div className="desktop-navigation-group desktop-navigation-end">
            <button
              className="topbar-icon"
              type="button"
              aria-label={selectedConversation?.isArchived ? "取消归档" : "归档对话"}
              title={selectedConversation?.isArchived ? "取消归档" : "归档对话"}
              disabled={!isPersistedConversation(selectedConversation)}
              onClick={() => void updateArchiveState()}
            >
              <Icon name="archive" size={16} />
            </button>
            <button
              className="topbar-icon danger"
              type="button"
              aria-label="删除对话"
              title="删除对话"
              disabled={!isPersistedConversation(selectedConversation)}
              onClick={() => void deleteConversation()}
            >
              <Icon name="trash" size={16} />
            </button>
            <button
              className="topbar-icon"
              type="button"
              aria-label={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              title={rightSidebarCollapsed ? "展开右侧边栏" : "收起右侧边栏"}
              aria-pressed={!rightSidebarCollapsed}
              onClick={() => setRightSidebarCollapsed((collapsed) => !collapsed)}
            >
              <Icon name="panel-right" size={18} />
            </button>
          </div>
        </header>
        <ConversationSidebar
          conversations={conversations}
          archivedConversations={archivedConversations}
          archivedLoading={archivedLoading}
          selected={selectedConversation}
          agentProfiles={agentProfiles}
          connectionStatus={connectionStatus}
          onCreate={createConversation}
          onSelect={(conversation) => void selectConversation(conversation)}
          onLoadArchived={() => loadArchivedConversations()}
          onArchive={(conversation) => updateArchiveState(conversation, true)}
          onRestore={(conversation) => updateArchiveState(conversation, false)}
          onSetPinned={(conversation, pinned) => updatePinState(conversation, pinned)}
          onDelete={(conversation) => deleteConversation(conversation)}
        />
        <ChatPanel
          conversation={selectedConversation}
          messages={messages}
          globalError={globalError}
          sending={sending}
          activeTaskId={activeRenderTaskId ?? activeTaskId}
          clarifyTaskId={clarifyTaskId}
          onOpenConversations={() => setConversationsOpen(true)}
          onArchive={() => void updateArchiveState()}
          onDelete={() => void deleteConversation()}
          onSend={sendMessage}
          onCancel={() => void cancelRun()}
          onClearError={() => setGlobalError("")}
          onAttachmentError={showError}
        />
        <ContextPane
          activePanel={contextPanel}
          workspacePath={workspacePath}
          workspaceItems={workspaceItems}
          workspaceFilePath={workspaceFilePath}
          workspaceContent={workspaceContent}
          workspaceDirty={workspaceDirty}
          browserSnapshot={browserSnapshot}
          browserFrameSeed={browserFrameSeed}
          onOpenConversations={() => setConversationsOpen(true)}
          onSelectPanel={setContextPanel}
          onWorkspacePath={() => {
            const parent = workspaceParentPath();
            if (parent && parent !== workspacePathRef.current) void loadWorkspace(parent);
          }}
          onWorkspaceItem={(item) => {
            if (item.isDirectory) void loadWorkspace(item.path);
            else void openWorkspaceFile(item.path);
          }}
          onWorkspaceRefresh={() => void loadWorkspace()}
          onWorkspaceContent={(content) => {
            setWorkspaceContent(content);
            setWorkspaceDirty(true);
          }}
          onWorkspaceSave={() => void saveWorkspaceFile()}
          onBrowserAction={(payload) => void browserAction(payload)}
          onBrowserRefresh={() => void refreshBrowser()}
        />

        <nav className="mobile-nav" aria-label="Web Chat 区域">
          {(["chat", "workspace", "browser"] as MobileSection[]).map((section) => (
            <button
              className={mobileSection === section ? "active" : ""}
              type="button"
              onClick={() => selectMobileSection(section)}
              key={section}
            >
              <Icon name={MOBILE_SECTION_ICON[section]} size={18} />
              <span>{{ chat: "聊天", workspace: "工作区", browser: "浏览器" }[section]}</span>
            </button>
          ))}
        </nav>
        <button
          className="conversation-scrim"
          type="button"
          aria-label="关闭对话列表"
          onClick={() => setConversationsOpen(false)}
        />
      </div>
      {toast && <div className="toast" role="status">{toast}</div>}
    </>
  );
}
