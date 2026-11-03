import {
  Archive,
  ArchiveRestore,
  Pin,
  PinOff,
  Trash2,
} from "lucide-react";
import {
  useEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type MouseEvent as ReactMouseEvent,
  type RefObject,
} from "react";
import { isAgentConversationMode } from "../conversationDraft";
import { conversationKey, modeLabel, relativeDate } from "../format";
import type {
  AgentProfile,
  ConnectionStatus,
  Conversation,
  ConversationCreateTarget,
} from "../types";
import { Icon, type IconName } from "./Icon";

interface ConversationSidebarProps {
  conversations: Conversation[];
  archivedConversations: Conversation[];
  archivedLoading: boolean;
  selected: Conversation | null;
  agentProfiles: AgentProfile[];
  connectionStatus: ConnectionStatus;
  onCreate: (target: ConversationCreateTarget) => void;
  onSelect: (conversation: Conversation) => void;
  onLoadArchived: () => Promise<void>;
  onArchive: (conversation: Conversation) => Promise<void>;
  onRestore: (conversation: Conversation) => Promise<void>;
  onSetPinned: (conversation: Conversation, pinned: boolean) => Promise<void>;
  onDelete: (conversation: Conversation) => Promise<void>;
}

interface ContextMenuState {
  conversation: Conversation;
  x: number;
  y: number;
}

const STATUS_LABELS: Record<ConnectionStatus, string> = {
  online: "实时连接正常",
  offline: "连接中断，正在重试",
  connecting: "正在连接实时事件",
};

const SECTION_ORDER = [
  "pinned",
  "codex",
  "deepseek",
  "claude",
  "opencode",
  "pi",
  "acp",
  "omni",
  "chat",
] as const;

type ConversationSection = typeof SECTION_ORDER[number];

const SECTION_LABELS: Record<ConversationSection, string> = {
  pinned: "置顶会话",
  codex: "Codex",
  // Keep retired Agent history identifiable without offering new conversations.
  deepseek: "DeepSeek Harness",
  claude: "Claude Code",
  opencode: "OpenCode",
  pi: "Pi",
  acp: "Agent",
  omni: "小万",
  chat: "纯聊天",
};

const SECTION_ICONS: Record<Exclude<ConversationSection, "pinned">, IconName> = {
  codex: "codex",
  deepseek: "deepseek",
  claude: "claude",
  opencode: "opencode",
  pi: "pi",
  acp: "agent",
  omni: "agent",
  chat: "chat",
};

function agentIcon(agentId?: string): IconName {
  if (agentId === "codex-acp") return "codex";
  if (agentId === "claude-code-acp") return "claude";
  if (agentId === "opencode-acp") return "opencode";
  if (agentId === "pi-acp") return "pi";
  if (agentId === "deepseek-harness-acp") return "deepseek";
  return "agent";
}

function conversationSection(conversation: Conversation): ConversationSection {
  if (conversation.isPinned) return "pinned";
  if (isAgentConversationMode(conversation.mode)) {
    if (!conversation.agentId || conversation.agentId === "codex-acp") return "codex";
    if (conversation.agentId === "claude-code-acp") return "claude";
    if (conversation.agentId === "opencode-acp") return "opencode";
    if (conversation.agentId === "pi-acp") return "pi";
    if (conversation.agentId === "deepseek-harness-acp") return "deepseek";
    return "acp";
  }
  if (conversation.mode === "chat_only") return "chat";
  return "omni";
}

function clampMenuPosition(value: number, size: number, viewportSize: number): number {
  const margin = 8;
  return Math.max(margin, Math.min(value, viewportSize - size - margin));
}

export function ConversationSidebar({
  conversations,
  archivedConversations,
  archivedLoading,
  selected,
  agentProfiles,
  connectionStatus,
  onCreate,
  onSelect,
  onLoadArchived,
  onArchive,
  onRestore,
  onSetPinned,
  onDelete,
}: ConversationSidebarProps) {
  const [search, setSearch] = useState("");
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(new Set());
  const [createMenuOpen, setCreateMenuOpen] = useState(false);
  const [archivePanelOpen, setArchivePanelOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<ContextMenuState | null>(null);
  const [busyConversationKeys, setBusyConversationKeys] = useState<Set<string>>(new Set());
  const createMenuAnchorRef = useRef<HTMLDivElement>(null);
  const createButtonRef = useRef<HTMLButtonElement>(null);
  const createMenuRef = useRef<HTMLDivElement>(null);
  const archivePanelAnchorRef = useRef<HTMLDivElement>(null);
  const archiveButtonRef = useRef<HTMLButtonElement>(null);
  const contextMenuRef = useRef<HTMLDivElement>(null);
  const contextMenuTriggerRef = useRef<HTMLButtonElement | null>(null);
  const query = search.trim().toLowerCase();
  const createOptions = useMemo(() => {
    const profiles = agentProfiles;
    return [
      {
        key: "omni",
        target: { mode: "normal", agentId: "xiaowan-acp" } as ConversationCreateTarget,
        label: "小万",
        icon: "agent" as IconName,
      },
      ...profiles
        // Xiaowan already has the entry above; preserve the native catalog
        // order for every other Agent.
        .filter((profile) => profile.enabled !== false && profile.id !== "xiaowan-acp")
        .map((profile) => ({
          key: profile.id,
          target: {
            mode: "codex",
            agentId: profile.id,
          } as ConversationCreateTarget,
          label: profile.name,
          icon: agentIcon(profile.id),
        })),
      {
        key: "chat",
        target: { mode: "chat_only", agentId: "xiaowan-acp" } as ConversationCreateTarget,
        label: "纯聊天模式",
        icon: "chat" as IconName,
      },
    ];
  }, [agentProfiles]);

  useEffect(() => {
    if (!createMenuOpen) return undefined;
    const focusFrame = window.requestAnimationFrame(() => {
      createMenuRef.current?.querySelector<HTMLButtonElement>("[role='menuitem']")?.focus();
    });
    const handlePointerDown = (event: PointerEvent) => {
      if (!createMenuAnchorRef.current?.contains(event.target as Node)) {
        setCreateMenuOpen(false);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setCreateMenuOpen(false);
      createButtonRef.current?.focus();
    };
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [createMenuOpen]);

  useEffect(() => {
    if (!archivePanelOpen) return undefined;
    const handlePointerDown = (event: PointerEvent) => {
      if (!archivePanelAnchorRef.current?.contains(event.target as Node)) {
        setArchivePanelOpen(false);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setArchivePanelOpen(false);
      archiveButtonRef.current?.focus();
    };
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [archivePanelOpen]);

  useEffect(() => {
    if (!contextMenu) return undefined;
    const focusFrame = window.requestAnimationFrame(() => {
      contextMenuRef.current?.querySelector<HTMLButtonElement>("[role='menuitem']")?.focus();
    });
    const handlePointerDown = (event: PointerEvent) => {
      if (!contextMenuRef.current?.contains(event.target as Node)) {
        setContextMenu(null);
      }
    };
    const handleKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key !== "Escape") return;
      event.preventDefault();
      setContextMenu(null);
      contextMenuTriggerRef.current?.focus();
    };
    const closeMenu = () => setContextMenu(null);
    document.addEventListener("pointerdown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    window.addEventListener("blur", closeMenu);
    window.addEventListener("resize", closeMenu);
    window.addEventListener("scroll", closeMenu, true);
    return () => {
      window.cancelAnimationFrame(focusFrame);
      document.removeEventListener("pointerdown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
      window.removeEventListener("blur", closeMenu);
      window.removeEventListener("resize", closeMenu);
      window.removeEventListener("scroll", closeMenu, true);
    };
  }, [contextMenu]);

  function handleMenuKeyDown(
    event: KeyboardEvent<HTMLDivElement>,
    menuRef: RefObject<HTMLDivElement | null>,
  ) {
    if (!["ArrowDown", "ArrowUp", "Home", "End"].includes(event.key)) return;
    const items = [...(menuRef.current?.querySelectorAll<HTMLButtonElement>("[role='menuitem']") ?? [])];
    if (!items.length) return;
    event.preventDefault();
    const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement);
    const nextIndex = event.key === "Home"
      ? 0
      : event.key === "End"
        ? items.length - 1
        : event.key === "ArrowDown"
          ? currentIndex < 0 ? 0 : (currentIndex + 1) % items.length
          : currentIndex < 0 ? items.length - 1 : (currentIndex - 1 + items.length) % items.length;
    items[nextIndex].focus();
  }

  function createConversation(target: ConversationCreateTarget) {
    setCreateMenuOpen(false);
    onCreate(target);
  }

  function toggleCreateMenu() {
    setArchivePanelOpen(false);
    setContextMenu(null);
    setCreateMenuOpen((open) => !open);
  }

  function toggleArchivePanel() {
    const opening = !archivePanelOpen;
    setCreateMenuOpen(false);
    setContextMenu(null);
    setArchivePanelOpen(opening);
    if (opening) void onLoadArchived();
  }

  function toggleSection(id: string) {
    setCollapsedSections((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function openContextMenuAt(
    conversation: Conversation,
    trigger: HTMLButtonElement,
    x: number,
    y: number,
  ) {
    contextMenuTriggerRef.current = trigger;
    setCreateMenuOpen(false);
    setArchivePanelOpen(false);
    setContextMenu({
      conversation,
      x: clampMenuPosition(x, 176, window.innerWidth),
      y: clampMenuPosition(y, 132, window.innerHeight),
    });
  }

  function openContextMenu(
    event: ReactMouseEvent<HTMLButtonElement>,
    conversation: Conversation,
  ) {
    event.preventDefault();
    event.stopPropagation();
    openContextMenuAt(conversation, event.currentTarget, event.clientX, event.clientY);
  }

  function handleConversationKeyDown(
    event: KeyboardEvent<HTMLButtonElement>,
    conversation: Conversation,
  ) {
    if (event.key !== "ContextMenu" && !(event.shiftKey && event.key === "F10")) return;
    event.preventDefault();
    const rect = event.currentTarget.getBoundingClientRect();
    openContextMenuAt(conversation, event.currentTarget, rect.left + 24, rect.top + rect.height / 2);
  }

  async function runConversationAction(
    conversation: Conversation,
    action: () => Promise<void>,
  ) {
    const key = conversationKey(conversation);
    setBusyConversationKeys((current) => new Set(current).add(key));
    try {
      await action();
    } finally {
      setBusyConversationKeys((current) => {
        const next = new Set(current);
        next.delete(key);
        return next;
      });
    }
  }

  function runContextMenuAction(action: (conversation: Conversation) => Promise<void>) {
    if (!contextMenu) return;
    const { conversation } = contextMenu;
    setContextMenu(null);
    void runConversationAction(conversation, () => action(conversation));
  }

  const sections = useMemo(() => {
    const visible = query
      ? conversations.filter((conversation) => (
        [conversation.title, conversation.summary, conversation.lastMessage]
          .some((value) => String(value ?? "").toLowerCase().includes(query))
      ))
      : conversations;
    return SECTION_ORDER
      .map((id) => ({
        id,
        items: visible.filter((conversation) => conversationSection(conversation) === id),
      }))
      .filter((section) => section.items.length > 0);
  }, [conversations, query]);
  const resultCount = sections.reduce((total, section) => total + section.items.length, 0);

  return (
    <aside className="conversation-pane">
      <div className="conversation-toolbar">
        <div className="search-field">
          <Icon name="search" size={17} />
          <input
            type="search"
            aria-label="搜索对话"
            placeholder="搜索对话"
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
          {search && (
            <button className="search-clear" type="button" aria-label="清除搜索" onClick={() => setSearch("")}>
              <Icon name="x" size={14} />
            </button>
          )}
        </div>
        <div
          className={`archive-popover-anchor${archivePanelOpen ? " expanded" : ""}`}
          ref={archivePanelAnchorRef}
        >
          <button
            className={`sidebar-round-button${archivePanelOpen ? " active" : ""}`}
            type="button"
            aria-label="查看归档对话"
            aria-haspopup="dialog"
            aria-expanded={archivePanelOpen}
            aria-controls={archivePanelOpen ? "archived-conversation-card" : undefined}
            title="查看归档对话"
            ref={archiveButtonRef}
            onClick={toggleArchivePanel}
          >
            <Archive aria-hidden="true" size={17} strokeWidth={1.8} />
          </button>
          {archivePanelOpen && (
            <section
              className="archive-popover"
              id="archived-conversation-card"
              role="dialog"
              aria-labelledby="archived-conversation-title"
            >
              <header className="archive-popover-header">
                <span>
                  <strong id="archived-conversation-title">已归档</strong>
                  <small>{archivedConversations.length}</small>
                </span>
                <button
                  type="button"
                  aria-label="关闭归档会话"
                  onClick={() => {
                    setArchivePanelOpen(false);
                    archiveButtonRef.current?.focus();
                  }}
                >
                  <Icon name="x" size={14} />
                </button>
              </header>
              <div className="archive-popover-list" aria-busy={archivedLoading} aria-live="polite">
                {archivedLoading ? (
                  <div className="archive-popover-status">正在加载归档会话…</div>
                ) : archivedConversations.length ? (
                  archivedConversations.map((conversation) => {
                    const key = conversationKey(conversation);
                    const busy = busyConversationKeys.has(key);
                    return (
                      <article className="archive-conversation-item" key={key}>
                        <span className="archive-conversation-copy">
                          <strong>{conversation.title || "新对话"}</strong>
                          <time>{relativeDate(conversation.updatedAt)}</time>
                        </span>
                        <span className="archive-conversation-actions">
                          <button
                            className="archive-item-action restore"
                            type="button"
                            aria-label={`恢复“${conversation.title || "新对话"}”`}
                            title="恢复会话"
                            disabled={busy}
                            onClick={() => void runConversationAction(
                              conversation,
                              () => onRestore(conversation),
                            )}
                          >
                            <ArchiveRestore aria-hidden="true" size={16} strokeWidth={1.8} />
                          </button>
                          <button
                            className="archive-item-action delete"
                            type="button"
                            aria-label={`删除“${conversation.title || "新对话"}”`}
                            title="删除会话"
                            disabled={busy}
                            onClick={() => void runConversationAction(
                              conversation,
                              () => onDelete(conversation),
                            )}
                          >
                            <Trash2 aria-hidden="true" size={16} strokeWidth={1.8} />
                          </button>
                        </span>
                      </article>
                    );
                  })
                ) : (
                  <div className="archive-popover-status empty">
                    <Archive aria-hidden="true" size={20} strokeWidth={1.7} />
                    <span>暂无归档会话</span>
                  </div>
                )}
              </div>
            </section>
          )}
        </div>
        <div
          className={`new-conversation-anchor${createMenuOpen ? " expanded" : ""}`}
          ref={createMenuAnchorRef}
          onBlur={(event) => {
            if (!event.currentTarget.contains(event.relatedTarget as Node | null)) {
              setCreateMenuOpen(false);
            }
          }}
        >
          <button
            className="sidebar-round-button primary"
            type="button"
            aria-label="新建对话"
            aria-haspopup="menu"
            aria-expanded={createMenuOpen}
            aria-controls={createMenuOpen ? "new-conversation-menu" : undefined}
            title="新建对话"
            ref={createButtonRef}
            onClick={toggleCreateMenu}
          >
            <Icon name="plus" size={18} />
          </button>
          {createMenuOpen && (
            <div
              className="new-conversation-menu"
              id="new-conversation-menu"
              role="menu"
              aria-label="选择新对话模式"
              ref={createMenuRef}
              onKeyDown={(event) => handleMenuKeyDown(event, createMenuRef)}
            >
              {createOptions.map((option) => (
                <button
                  className="new-conversation-menu-item"
                  type="button"
                  role="menuitem"
                  key={option.key}
                  onClick={() => createConversation(option.target)}
                >
                  <span className="new-conversation-menu-icon">
                    <Icon name={option.icon} size={17} />
                  </span>
                  <span>{option.label}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </div>

      <div className="conversation-list" aria-live="polite">
        {query && resultCount > 0 && (
          <div className="search-summary">
            <span>搜索结果</span>
            <span>{resultCount}</span>
          </div>
        )}
        {!resultCount && (
          <div className="list-empty">
            <Icon name={query ? "search" : "agent"} size={24} />
            <strong>{query ? "没有找到相关对话" : "还没有对话"}</strong>
            <span>{query ? "换个关键词试试" : "点击右上角开始新对话"}</span>
          </div>
        )}
        {sections.map((section) => {
          const collapsed = collapsedSections.has(section.id);
          return (
            <section className="conversation-section" key={section.id}>
              <button
                className="conversation-section-header"
                type="button"
                aria-expanded={!collapsed}
                onClick={() => toggleSection(section.id)}
              >
                {section.id === "pinned" ? (
                  <Pin aria-hidden="true" size={14} strokeWidth={1.8} />
                ) : (
                  <Icon name={SECTION_ICONS[section.id]} size={14} />
                )}
                <span>{SECTION_LABELS[section.id]}</span>
                <small>{section.items.length}</small>
                <Icon
                  name="chevron-down"
                  size={14}
                  className={`section-chevron${collapsed ? " collapsed" : ""}`}
                />
              </button>
              <div className={`section-body${collapsed ? " collapsed" : ""}`}>
                {section.items.map((conversation) => {
                  const active = conversationKey(conversation) === conversationKey(selected);
                  const preview = conversation.summary
                    || conversation.lastMessage
                    || modeLabel(conversation.mode, conversation.agentId);
                  return (
                    <button
                      key={conversationKey(conversation)}
                      className={`conversation-item${active ? " active" : ""}`}
                      type="button"
                      aria-keyshortcuts="Shift+F10"
                      onClick={() => {
                        setContextMenu(null);
                        onSelect(conversation);
                      }}
                      onContextMenu={(event) => openContextMenu(event, conversation)}
                      onKeyDown={(event) => handleConversationKeyDown(event, conversation)}
                    >
                      <span className="conversation-item-heading">
                        <strong>{conversation.title || "新对话"}</strong>
                        <time>{relativeDate(conversation.updatedAt)}</time>
                      </span>
                      {query && <p>{preview}</p>}
                    </button>
                  );
                })}
              </div>
            </section>
          );
        })}
      </div>

      <footer className="connection-footer">
        <span className={`connection-dot ${connectionStatus === "connecting" ? "" : connectionStatus}`} />
        <span>{STATUS_LABELS[connectionStatus]}</span>
      </footer>

      {contextMenu && (
        <div
          className="conversation-context-menu"
          role="menu"
          aria-label={`“${contextMenu.conversation.title || "新对话"}”操作`}
          ref={contextMenuRef}
          style={{ left: contextMenu.x, top: contextMenu.y }}
          onKeyDown={(event) => handleMenuKeyDown(event, contextMenuRef)}
        >
          <button
            type="button"
            role="menuitem"
            onClick={() => runContextMenuAction(onArchive)}
          >
            <Archive aria-hidden="true" size={16} strokeWidth={1.8} />
            <span>归档</span>
          </button>
          <button
            type="button"
            role="menuitem"
            onClick={() => runContextMenuAction((conversation) => (
              onSetPinned(conversation, !conversation.isPinned)
            ))}
          >
            {contextMenu.conversation.isPinned ? (
              <PinOff aria-hidden="true" size={16} strokeWidth={1.8} />
            ) : (
              <Pin aria-hidden="true" size={16} strokeWidth={1.8} />
            )}
            <span>{contextMenu.conversation.isPinned ? "取消置顶" : "置顶"}</span>
          </button>
          <div className="conversation-context-menu-separator" role="separator" />
          <button
            className="danger"
            type="button"
            role="menuitem"
            onClick={() => runContextMenuAction(onDelete)}
          >
            <Trash2 aria-hidden="true" size={16} strokeWidth={1.8} />
            <span>删除</span>
          </button>
        </div>
      )}
    </aside>
  );
}
