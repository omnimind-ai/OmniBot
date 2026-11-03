import {
  useEffect,
  useRef,
  useState,
  type ChangeEvent,
  type FormEvent,
  type KeyboardEvent,
} from "react";
import { agentAvatarUrl, isRecord } from "../api";
import { formatBytes, markdownToHtml, messageContent, messageTime } from "../format";
import { buildRunTimeline } from "../runTimeline";
import type { Attachment, ChatMessage, Conversation } from "../types";
import {
  ComposerAttachmentIcon,
  ComposerSendIcon,
  ComposerStopIcon,
} from "./ComposerIcons";
import { Icon } from "./Icon";

interface ChatPanelProps {
  conversation: Conversation | null;
  messages: ChatMessage[];
  globalError: string;
  unavailableMessage?: string;
  sending: boolean;
  activeTaskId: string | null;
  clarifyTaskId: string | null;
  onOpenConversations: () => void;
  onArchive: () => void;
  onDelete: () => void;
  onSend: (text: string, attachments: Attachment[]) => Promise<boolean>;
  onCancel: () => void;
  onClearError: () => void;
  onAttachmentError: (error: unknown) => void;
}

const GREETING_WORDS = ["聊天", "执行", "构建", "探索", "规划", "总结", "检索", "记忆"];
const WORD_ROTATE_INTERVAL = 1800;
const WORD_SPIN_DURATION = 460;

function formatThinkTime(seconds: number): string {
  if (seconds < 60) return `${seconds}秒`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${m}分${s}秒`;
}

function formatRunTime(seconds: number): string {
  if (seconds < 1) return "";
  if (seconds < 60) return `${seconds}s`;
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  if (m < 60) return s > 0 ? `${m}m ${s}s` : `${m}m`;
  const h = Math.floor(m / 60);
  const rm = m % 60;
  return rm > 0 ? `${h}h ${rm}m` : `${h}h`;
}

function useElapsedTime(start: number, end: number, active: boolean): number {
  const [elapsed, setElapsed] = useState(() => {
    if (!start) return 0;
    return Math.max(0, Math.round(((end > 0 ? end : Date.now()) - start) / 1000));
  });
  useEffect(() => {
    if (!active || !start) return undefined;
    const update = () => setElapsed(Math.max(0, Math.round((Date.now() - start) / 1000)));
    update();
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [active, start]);
  return elapsed;
}

/** 复刻 Flutter _SlotWordRotator: 随机初始词, 每 1800ms 随机换词, 460ms 上滑+淡入淡出 */
function SlotWordRotator({ words }: { words: string[] }) {
  const [current, setCurrent] = useState(() => Math.floor(Math.random() * words.length));
  const [previous, setPrevious] = useState<number | null>(null);
  const currentRef = useRef(current);
  currentRef.current = current;

  useEffect(() => {
    if (words.length <= 1) return undefined;
    const timer = window.setInterval(() => {
      let next = Math.floor(Math.random() * words.length);
      if (next === currentRef.current) next = (next + 1) % words.length;
      setPrevious(currentRef.current);
      setCurrent(next);
    }, WORD_ROTATE_INTERVAL);
    return () => window.clearInterval(timer);
  }, [words]);

  useEffect(() => {
    if (previous === null) return undefined;
    const timer = window.setTimeout(() => setPrevious(null), WORD_SPIN_DURATION);
    return () => window.clearTimeout(timer);
  }, [previous]);

  return (
    <span className="greeting-word">
      {/* 隐藏测量层: 容器宽度始终取最长词, 避免换词时抖动 */}
      {words.map((word) => (
        <span className="word-sizer" aria-hidden="true" key={word}>{word}</span>
      ))}
      {previous !== null && (
        <span className="word-out" aria-hidden="true">{words[previous]}</span>
      )}
      <span className={previous !== null ? "word-in" : "word-current"}>{words[current]}</span>
    </span>
  );
}

function EmptyGreeting() {
  return (
    <div className="empty-state">
      <div className="empty-greeting">
        <p>你好👋，我是小万</p>
        <p>我可以帮助你 <SlotWordRotator words={GREETING_WORDS} /></p>
      </div>
    </div>
  );
}

function AgentAvatar({ className }: { className: string }) {
  const [failed, setFailed] = useState(false);
  return (
    <span className={className}>
      {failed ? (
        <Icon name="agent" size={16} />
      ) : (
        <img
          alt=""
          draggable={false}
          src={agentAvatarUrl()}
          onError={() => setFailed(true)}
        />
      )}
    </span>
  );
}

function fileToAttachment(file: File): Promise<Attachment> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve({
      fileName: file.name,
      mimeType: file.type || "application/octet-stream",
      size: file.size,
      dataUrl: String(reader.result),
      isImage: file.type.startsWith("image/"),
    });
    reader.onerror = () => reject(reader.error ?? new Error(`无法读取 ${file.name}`));
    reader.readAsDataURL(file);
  });
}

function attachmentName(attachment: Record<string, unknown>): string {
  return String(attachment.fileName ?? attachment.name ?? "附件");
}

function attachmentImage(attachment: Record<string, unknown>): string {
  const source = String(attachment.dataUrl ?? attachment.url ?? "");
  const mimeType = String(attachment.mimeType ?? attachment.type ?? "");
  const isImage = attachment.isImage === true
    || mimeType.startsWith("image/")
    || source.startsWith("data:image/")
    || /\.(avif|gif|jpe?g|png|webp)(?:[?#]|$)/i.test(source);
  return isImage && (source.startsWith("data:image/") || source.startsWith("https://") || source.startsWith("http://"))
    ? source
    : "";
}

function MessageAttachments({ attachments }: { attachments: Record<string, unknown>[] }) {
  if (!attachments.length) return null;
  return (
    <div className="message-attachments">
      {attachments.map((attachment, index) => {
        const image = attachmentImage(attachment);
        const name = attachmentName(attachment);
        return image ? (
          <img className="message-image" src={image} alt={name} key={`${name}-${index}`} />
        ) : (
          <span className="attachment-chip" key={`${name}-${index}`}>
            <Icon name="file" size={15} />
            <span>{name}</span>
          </span>
        );
      })}
    </div>
  );
}

/** 深度思考卡 —— 带计时文案 (正在思考/思考完成 + 用时Xs) + shimmer + 可选头像 */
function DeepThinkingMessage({
  card,
  classes,
  active = false,
  showAvatar = true,
}: {
  card: Record<string, unknown>;
  classes: string;
  active?: boolean;
  showAvatar?: boolean;
}) {
  const startTime = Number(card.startTime ?? 0);
  const endTime = Number(card.endTime ?? 0);
  const stage = Number(card.stage ?? 0);
  const stageCompleted = stage === 4 || stage === 5;
  const completed = stageCompleted && !active;
  const loading = active || (
    !stageCompleted
    && (card.isLoading === true || stage === 0 || (stage > 0 && stage < 4))
  );
  const thinkingText = String(card.thinkingContent ?? "").trim();
  const thinkingBodyRef = useRef<HTMLDivElement>(null);
  const [expanded, setExpanded] = useState(loading);
  const elapsed = useElapsedTime(startTime, loading ? 0 : endTime, loading);
  const timeLabel = elapsed > 0 ? formatThinkTime(elapsed) : "";
  const title = loading ? "正在思考" : "思考完成";
  const label = timeLabel ? `${title} (用时${timeLabel})` : title;

  useEffect(() => {
    if (loading) {
      setExpanded(true);
    } else if (completed) {
      setExpanded(false);
    }
  }, [completed, loading]);

  useEffect(() => {
    const body = thinkingBodyRef.current;
    if (loading && body) body.scrollTop = body.scrollHeight;
  }, [loading, thinkingText]);

  return (
    <article className={classes}>
      <div className="message-content">
        <details
          className={`message-reasoning${loading ? " streaming" : ""}`}
          open={expanded}
          onToggle={(event) => {
            const nextExpanded = event.currentTarget.open;
            if (loading && !nextExpanded) {
              event.currentTarget.open = true;
              return;
            }
            setExpanded(nextExpanded);
          }}
        >
          <summary>
            {showAvatar && <AgentAvatar className="reasoning-avatar" />}
            <span className="reasoning-toggle-label">
              <span className="reasoning-label">{label}</span>
              <Icon className="reasoning-chevron" name="chevron-down" size={16} />
            </span>
          </summary>
          {loading && !thinkingText && (
            <span className="thinking-dots" role="status" aria-label="正在思考">
              <span /><span /><span />
            </span>
          )}
          {thinkingText && (
            <div className="reasoning-body" aria-live="polite" ref={thinkingBodyRef}>
              <div className="message-text" dangerouslySetInnerHTML={{ __html: markdownToHtml(thinkingText) }} />
            </div>
          )}
        </details>
      </div>
    </article>
  );
}

function statusLabel(status: unknown): string {
  return ({
    running: "运行中",
    completed: "已完成",
    success: "已完成",
    error: "失败",
    timeout: "超时",
    interrupted: "已中断",
    cancelled: "已停止",
  } as Record<string, string>)[String(status)] ?? String(status || "已完成");
}

function statusClass(status: unknown): string {
  const value = String(status ?? "");
  if (value === "running") return "running";
  if (value === "success" || value === "completed") return "success";
  if (value === "error") return "error";
  if (value === "timeout") return "timeout";
  if (value === "interrupted" || value === "cancelled") return "interrupted";
  return "running";
}

function toolTypeLabel(card: Record<string, unknown>): string {
  const raw = `${String(card.toolType ?? "")} ${String(card.type ?? "")}`.toLowerCase();
  if (/terminal|shell|command|process/.test(raw)) return "终端";
  if (/browser|web|navigate/.test(raw)) return "浏览器";
  if (/search/.test(raw)) return "搜索";
  if (/file|read|write|edit/.test(raw)) return "文件";
  if (/subagent/.test(raw)) return "SubAgent";
  if (/mcp/.test(raw)) return "MCP";
  if (/codex/.test(raw)) return "Codex";
  return "工具";
}

function toolIcon(card: Record<string, unknown>): "terminal" | "browser" | "search" | "file" | "agent" | "workspace" {
  const raw = `${String(card.toolType ?? "")} ${String(card.type ?? "")}`.toLowerCase();
  if (/terminal|shell|command|process/.test(raw)) return "terminal";
  if (/browser|web|navigate/.test(raw)) return "browser";
  if (/search/.test(raw)) return "search";
  if (/file|read|write|edit/.test(raw)) return "file";
  if (/subagent|agent|mcp/.test(raw)) return "agent";
  return "workspace";
}

function Message({
  message,
  active = false,
  suppressReasoning = false,
  suppressThinkingAvatar = false,
}: {
  message: ChatMessage;
  active?: boolean;
  suppressReasoning?: boolean;
  suppressThinkingAvatar?: boolean;
}) {
  const content = messageContent(message);
  const isUser = Number(message.user) === 1;
  const rawCard = isRecord(content.cardData) ? content.cardData : null;
  const card = rawCard ?? content;
  const attachments = Array.isArray(content.attachments)
    ? content.attachments.filter(isRecord)
    : [];
  const reasoning = String(message.reasoning_content ?? message.reasoningContent ?? "").trim();
  const classes = `message-row ${isUser ? "user" : "assistant"}${message.isError ? " error" : ""}`;
  const isCard = Number(message.type) === 2 || rawCard;
  const cardType = String(card.type ?? "");

  // 深度思考卡 → 可折叠思考块 (不要作为工具卡显示)
  if (isCard && cardType === "deep_thinking") {
    return (
      <DeepThinkingMessage
        card={card}
        classes={classes}
        active={active}
        showAvatar={!suppressThinkingAvatar}
      />
    );
  }

  // 工具调用卡 (agent_tool_summary 及其他卡片类型)
  if (isCard) {
    const title = card.toolTitle ?? card.toolName ?? card.displayName ?? card.title ?? card.toolType ?? "工具运行";
    const status = card.status ?? (message.isLoading ? "running" : "completed");
    const running = String(status) === "running";
    return (
      <article className={`${classes} card-message`}>
        <div className="message-content">
          <details className={`tool-message status-${statusClass(status)}`} open={running}>
            <summary>
              <span className="tool-icon"><Icon name={toolIcon(card)} size={16} /></span>
              <span className="tool-heading">
                <strong className={running ? "shimmer" : ""}>{String(title)}</strong>
              </span>
              <span className="tool-status-toggle">
                <span className="tool-status">{running ? toolTypeLabel(card) : statusLabel(status)}</span>
                <Icon className="tool-chevron" name="chevron-down" size={14} />
              </span>
            </summary>
            <div className="tool-detail">
              <pre>{JSON.stringify(card, null, 2)}</pre>
            </div>
          </details>
        </div>
      </article>
    );
  }

  const text = String(content.text ?? "");
  const reasoningStreaming = active || Boolean(message.isLoading);
  return (
    <article className={classes}>
      <div className="message-content">
        {reasoning && !suppressReasoning && (
          <details className={`message-reasoning${reasoningStreaming ? " streaming" : ""}`} open={reasoningStreaming}>
            <summary>
              <span className="reasoning-toggle-label">
                <span className="reasoning-label">{reasoningStreaming ? "正在思考" : "思考过程"}</span>
                <Icon className="reasoning-chevron" name="chevron-down" size={16} />
              </span>
            </summary>
            <div className="reasoning-body">
              <div className="message-text" dangerouslySetInnerHTML={{ __html: markdownToHtml(reasoning) }} />
            </div>
          </details>
        )}
        {message.isLoading && !text ? (
          <span className="thinking-dots" role="status" aria-label="正在思考">
            <span /><span /><span />
          </span>
        ) : (
          text && (
            <div
              className="message-text"
              dangerouslySetInnerHTML={{ __html: markdownToHtml(text) }}
            />
          )
        )}
        <MessageAttachments attachments={attachments} />
      </div>
    </article>
  );
}

/* ---------------------------------------------------------------------------
 * Agent 运行分组折叠 (复刻 agent_run_timeline.dart / agent_run_group_message.dart)
 * 同一 parentTaskId 的思考、工具和中间文本共享一个折叠头，最终回复常驻显示。
 * ------------------------------------------------------------------------- */

interface RunGroup {
  taskId: string;
  processMessages: ChatMessage[];
  visibleMessages: ChatMessage[];
  startTime: number;
  endTime: number;
  active: boolean;
  hasThinking: boolean;
}

type RenderItem =
  | { kind: "single"; message: ChatMessage }
  | { kind: "run"; group: RunGroup };

function messageCardData(message: ChatMessage): Record<string, unknown> | null {
  const content = messageContent(message);
  if (isRecord(content.cardData)) return content.cardData;
  return Number(message.type) === 2 ? content : null;
}

function cardType(message: ChatMessage): string {
  return String(messageCardData(message)?.type ?? "").trim();
}

function isDeepThinkingMessage(message: ChatMessage): boolean {
  return cardType(message) === "deep_thinking";
}

function agentTaskIdFromEntryId(raw: unknown): string | null {
  const id = String(raw ?? "").trim();
  if (!id) return null;
  for (const suffix of ["-assistant", "-clarify", "-permission", "-error", "-thinking", "-text"]) {
    if (id.endsWith(suffix)) return id.slice(0, -suffix.length);
  }
  for (const marker of ["-thinking-", "-text-", "-tool-", "-permission-"]) {
    const index = id.indexOf(marker);
    if (index > 0) return id.slice(0, index);
  }
  return null;
}

function agentRunParentTaskId(message: ChatMessage): string | null {
  const content = messageContent(message);
  const card = messageCardData(message);
  const raw = message.streamMeta?.parentTaskId ?? card?.taskID ?? card?.taskId;
  const normalized = String(raw ?? "").trim();
  if (normalized) return normalized;
  if (Number(message.user) === 1) return null;
  return agentTaskIdFromEntryId(message.id)
    ?? agentTaskIdFromEntryId(message.contentId)
    ?? agentTaskIdFromEntryId(content.id);
}

function agentRunKind(message: ChatMessage): string {
  return String(message.streamMeta?.kind ?? "").trim().toLowerCase();
}

function wholeInt(value: unknown): number | null {
  if (typeof value === "number" && Number.isInteger(value)) return value;
  if (typeof value === "string" && /^-?\d+$/.test(value.trim())) return Number(value.trim());
  return null;
}

function positiveSuffixAfterMarker(value: string, marker: string): number | null {
  const index = value.lastIndexOf(marker);
  if (index < 0) return null;
  const parsed = Number(value.slice(index + marker.length).trim());
  return Number.isInteger(parsed) && parsed >= 1 ? parsed : null;
}

function phaseSequence(roundIndex: number, phaseOffset: number): number {
  return ((roundIndex - 1) * 3) + phaseOffset;
}

function entrySequenceFromAgentEntryId(raw: unknown): number | null {
  const id = String(raw ?? "").trim();
  if (!id) return null;
  const thinkingRound = positiveSuffixAfterMarker(id, "-thinking-");
  if (thinkingRound !== null) return phaseSequence(thinkingRound, 1);
  if (id.endsWith("-thinking")) return 1;
  const textRound = positiveSuffixAfterMarker(id, "-text-");
  if (textRound !== null) return phaseSequence(textRound, 2);
  if (id.endsWith("-text") || id.endsWith("-assistant")) return 2;
  const toolIndex = positiveSuffixAfterMarker(id, "-tool-");
  if (toolIndex !== null) return phaseSequence(toolIndex, 3);
  return null;
}

function agentRunSequence(message: ChatMessage): number {
  const content = messageContent(message);
  return wholeInt(message.streamMeta?.entrySeq)
    ?? wholeInt(message.streamMeta?.seq)
    ?? entrySequenceFromAgentEntryId(message.id)
    ?? entrySequenceFromAgentEntryId(message.contentId)
    ?? entrySequenceFromAgentEntryId(content.id)
    ?? -1;
}

function compareOldestFirst(left: ChatMessage, right: ChatMessage): number {
  const sequenceCompare = agentRunSequence(left) - agentRunSequence(right);
  return sequenceCompare || (messageTime(left) - messageTime(right));
}

function newestBySequence(messages: ChatMessage[]): ChatMessage {
  return messages.reduce((newest, candidate) => (
    compareOldestFirst(candidate, newest) >= 0 ? candidate : newest
  ));
}

function isAgentRunCandidateMessage(message: ChatMessage): boolean {
  if (Number(message.user) === 1) return false;
  if (Number(message.type) === 1) return Number(message.user) === 2;
  if (Number(message.type) !== 2) return false;
  return ["deep_thinking", "agent_tool_summary", "permission_section", "codex_request"].includes(cardType(message));
}

function isTerminalVisibleTextMessage(message: ChatMessage): boolean {
  if (message.streamMeta?.isFinal === true) return true;
  const kind = agentRunKind(message);
  return kind === "clarify_required"
    || kind === "permission_required"
    || kind === "error"
    || message.isError === true;
}

function isLegacyTextSnapshotFallbackCandidate(message: ChatMessage): boolean {
  if (agentRunKind(message) !== "text_snapshot") return false;
  return !("isFinal" in (message.streamMeta ?? {})) || message.streamMeta?.isFinal === true;
}

function isCancelledTextMessage(message: ChatMessage): boolean {
  const text = String(messageContent(message).text ?? "").trim().toLowerCase();
  return text === "任务已取消" || text === "task canceled" || text === "task cancelled";
}

function isCodexRequestMessage(message: ChatMessage): boolean {
  return cardType(message) === "codex_request";
}

function resolvePrimaryVisibleMessage(
  taskMessages: ChatMessage[],
  active: boolean,
  requestMessages: ChatMessage[],
): ChatMessage | null {
  if (active) return requestMessages.length ? newestBySequence(requestMessages) : null;
  const aiTextMessages = taskMessages.filter((message) => Number(message.type) === 1 && Number(message.user) === 2);
  if (!aiTextMessages.length) return requestMessages.length ? newestBySequence(requestMessages) : null;
  const directFinalMatches = aiTextMessages.filter(isTerminalVisibleTextMessage);
  if (directFinalMatches.length) return newestBySequence(directFinalMatches);
  const fallbackTextSnapshots = aiTextMessages.filter(isLegacyTextSnapshotFallbackCandidate);
  if (fallbackTextSnapshots.length) return newestBySequence(fallbackTextSnapshots);
  const cancelledTextMessages = aiTextMessages.filter(isCancelledTextMessage);
  if (cancelledTextMessages.length) return newestBySequence(cancelledTextMessages);
  return requestMessages.length ? newestBySequence(requestMessages) : null;
}

function resolveVisibleMessages(taskMessages: ChatMessage[], primary: ChatMessage): ChatMessage[] {
  const visibleMessages = [primary];
  const primaryKind = agentRunKind(primary);
  if (primaryKind === "permission_required") {
    visibleMessages.push(...taskMessages.filter((message) => message !== primary && cardType(message) === "permission_section"));
  }
  if (primaryKind === "clarify_required" || primaryKind === "permission_required" || isCodexRequestMessage(primary)) {
    visibleMessages.push(...taskMessages.filter((message) => message !== primary && isCodexRequestMessage(message)));
  }
  return visibleMessages.sort(compareOldestFirst);
}

function buildRunGroup(messages: ChatMessage[], taskId: string, active: boolean): RunGroup | null {
  const taskMessages = messages.filter(
    (message) => agentRunParentTaskId(message) === taskId && isAgentRunCandidateMessage(message),
  );
  const requestMessages = taskMessages.filter(isCodexRequestMessage);
  if (taskMessages.length < 2 && !requestMessages.length) return null;
  const primary = resolvePrimaryVisibleMessage(taskMessages, active, requestMessages);
  const visibleMessages = primary
    ? resolveVisibleMessages(taskMessages, primary)
    : [];
  const timeline = buildRunTimeline(
    taskMessages,
    visibleMessages,
    active,
    compareOldestFirst,
  );
  if (!timeline) return null;
  const { processMessages } = timeline;
  if (
    !processMessages.length
    && visibleMessages.length < 2
    && (!primary || !isCodexRequestMessage(primary))
  ) return null;
  const times = taskMessages.map(messageTime).filter((time) => time > 0);
  return {
    taskId,
    processMessages,
    visibleMessages: timeline.visibleMessages,
    startTime: times.length ? Math.min(...times) : 0,
    endTime: times.length ? Math.max(...times) : 0,
    active,
    hasThinking: processMessages.some(isDeepThinkingMessage),
  };
}

function buildGroups(messages: ChatMessage[], activeTaskId: string | null): RenderItem[] {
  const items: RenderItem[] = [];
  const emittedTaskIds = new Set<string>();
  const normalizedActiveTaskId = activeTaskId?.trim() || null;
  for (const message of messages) {
    const taskId = agentRunParentTaskId(message);
    if (!taskId) {
      items.push({ kind: "single", message });
      continue;
    }
    if (emittedTaskIds.has(taskId)) {
      if (!isAgentRunCandidateMessage(message)) items.push({ kind: "single", message });
      continue;
    }
    const group = buildRunGroup(messages, taskId, taskId === normalizedActiveTaskId);
    if (!group) {
      items.push({ kind: "single", message });
      continue;
    }
    items.push({ kind: "run", group });
    emittedTaskIds.add(taskId);
  }
  return items;
}

function AgentRunGroup({ group }: { group: RunGroup }) {
  const [expanded, setExpanded] = useState(group.active);
  const elapsed = Math.max(0, Math.round((group.endTime - group.startTime) / 1000));
  const timeLabel = formatRunTime(elapsed);
  const label = timeLabel ? `已处理  ${timeLabel}` : "已处理";
  const groupMessages = [...group.processMessages, ...group.visibleMessages];
  const activeTailMessage = group.active && groupMessages.length
    ? newestBySequence(groupMessages)
    : null;

  useEffect(() => {
    setExpanded(group.active);
  }, [group.active]);

  return (
    <div className={`agent-run-group${expanded ? " expanded" : ""}`}>
      <button className="agent-run-header" type="button" aria-expanded={expanded} onClick={() => setExpanded((e) => !e)}>
        <AgentAvatar className="agent-run-avatar" />
        <span className="agent-run-title">
          <span className="agent-run-label">{label}</span>
          <Icon name="chevron-down" size={18} className="agent-run-chevron" />
        </span>
      </button>
      <div className="agent-run-process">
        {group.processMessages.map((msg, index) => (
          <Message
            message={msg}
            active={msg === activeTailMessage}
            suppressReasoning={group.hasThinking}
            suppressThinkingAvatar={isDeepThinkingMessage(msg)}
            key={String(msg.id ?? `${messageTime(msg)}-${index}`)}
          />
        ))}
      </div>
      {group.visibleMessages.map((msg, index) => (
        <Message
          message={msg}
          active={msg === activeTailMessage}
          suppressReasoning={group.hasThinking}
          key={String(msg.id ?? `${messageTime(msg)}-v${index}`)}
        />
      ))}
    </div>
  );
}

export function ChatPanel({
  conversation,
  messages,
  globalError,
  unavailableMessage,
  sending,
  activeTaskId,
  clarifyTaskId,
  onOpenConversations,
  onArchive,
  onDelete,
  onSend,
  onCancel,
  onClearError,
  onAttachmentError,
}: ChatPanelProps) {
  const [draft, setDraft] = useState("");
  const [attachments, setAttachments] = useState<Attachment[]>([]);
  const messageListRef = useRef<HTMLDivElement>(null);
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const attachmentInputRef = useRef<HTMLInputElement>(null);
  const sortedMessages = [...messages].sort((left, right) => messageTime(left) - messageTime(right));
  const normalizedActiveTaskId = activeTaskId?.trim() || null;
  const thinkingTaskIds = new Set(
    sortedMessages
      .filter(isDeepThinkingMessage)
      .map(agentRunParentTaskId)
      .filter((taskId): taskId is string => Boolean(taskId)),
  );
  const firstThinkingMessageByTask = new Map<string, ChatMessage>();
  sortedMessages
    .filter(isDeepThinkingMessage)
    .sort(compareOldestFirst)
    .forEach((message) => {
      const taskId = agentRunParentTaskId(message);
      if (taskId && !firstThinkingMessageByTask.has(taskId)) {
        firstThinkingMessageByTask.set(taskId, message);
      }
    });
  const activeTaskMessages = normalizedActiveTaskId
    ? sortedMessages.filter(
      (message) => (
        agentRunParentTaskId(message) === normalizedActiveTaskId
        && isAgentRunCandidateMessage(message)
      ),
    )
    : [];
  const activeTailMessage = activeTaskMessages.length
    ? newestBySequence(activeTaskMessages)
    : null;
  const canManageConversation = Number(conversation?.id ?? 0) > 0;
  const isProcessing = sending || Boolean(activeTaskId && !clarifyTaskId);
  const canSend = !unavailableMessage && !isProcessing && (clarifyTaskId ? Boolean(draft.trim()) : Boolean(draft.trim() || attachments.length));

  useEffect(() => {
    const list = messageListRef.current;
    if (list) list.scrollTop = list.scrollHeight;
  }, [messages, activeTaskId]);

  useEffect(() => {
    const textarea = textareaRef.current;
    if (!textarea) return;
    textarea.style.height = "auto";
    textarea.style.height = `${Math.min(textarea.scrollHeight, 96)}px`;
  }, [draft]);

  async function submit(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (!canSend) return;
    onClearError();
    const sent = await onSend(draft.trim(), attachments);
    if (sent) {
      setDraft("");
      setAttachments([]);
    }
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key === "Enter" && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault();
      void submit();
    }
  }

  async function addAttachments(event: ChangeEvent<HTMLInputElement>) {
    const files = [...(event.target.files ?? [])];
    event.target.value = "";
    if (!files.length) return;
    try {
      const nextAttachments = await Promise.all(files.map(fileToAttachment));
      setAttachments((current) => [...current, ...nextAttachments]);
    } catch (error) {
      onAttachmentError(error);
    }
  }

  return (
    <section className="chat-pane">
      <header className="chat-app-bar">
        <button
          className="appbar-icon menu-trigger"
          type="button"
          aria-label="打开对话列表"
          onClick={onOpenConversations}
        >
          <Icon name="menu" size={20} />
        </button>
        <div className="chat-header-actions">
          <button
            className="appbar-icon"
            type="button"
            aria-label={conversation?.isArchived ? "取消归档" : "归档对话"}
            title={conversation?.isArchived ? "取消归档" : "归档对话"}
            disabled={!canManageConversation}
            onClick={onArchive}
          >
            <Icon name="archive" size={18} />
          </button>
          <button
            className="appbar-icon danger"
            type="button"
            aria-label="删除对话"
            title="删除对话"
            disabled={!canManageConversation}
            onClick={onDelete}
          >
            <Icon name="trash" size={18} />
          </button>
        </div>
      </header>

      {globalError && <div className="global-error" role="alert">{globalError}</div>}

      <div className="message-list" aria-live="polite" ref={messageListRef}>
        {!sortedMessages.length && <EmptyGreeting />}
        {buildGroups(sortedMessages, activeTaskId).map((item, index) => {
          if (item.kind === "run") {
            return <AgentRunGroup group={item.group} key={`run-${item.group.taskId}`} />;
          }
          const taskId = agentRunParentTaskId(item.message);
          const isThinking = isDeepThinkingMessage(item.message);
          return (
            <Message
              message={item.message}
              active={item.message === activeTailMessage}
              suppressReasoning={thinkingTaskIds.has(taskId ?? "")}
              suppressThinkingAvatar={Boolean(
                isThinking
                && taskId
                && firstThinkingMessageByTask.get(taskId) !== item.message
              )}
              key={String(item.message.id ?? `${messageTime(item.message)}-${index}`)}
            />
          );
        })}
      </div>

      <div className="composer-region">
        {clarifyTaskId && (
          <div className="clarify-banner">Agent 正在等待你的补充说明，发送下一条消息后继续。</div>
        )}
        {unavailableMessage ? <p role="status">{unavailableMessage}</p> : (
        <form className="composer" onSubmit={(event) => void submit(event)}>
          {!!attachments.length && (
            <div className="attachment-list">
              {attachments.map((attachment, index) => (
                <div className={`composer-attachment${attachment.isImage ? " image" : ""}`} key={`${attachment.fileName}-${index}`}>
                  {attachment.isImage ? (
                    <img src={attachment.dataUrl} alt={attachment.fileName} />
                  ) : (
                    <>
                      <Icon name="file" size={16} />
                      <span>
                        <strong>{attachment.fileName}</strong>
                        <small>{formatBytes(attachment.size)}</small>
                      </span>
                    </>
                  )}
                  <button
                    type="button"
                    aria-label={`移除 ${attachment.fileName}`}
                    onClick={() => setAttachments((current) => current.filter((_, itemIndex) => itemIndex !== index))}
                  >
                    <Icon name="x" size={12} />
                  </button>
                </div>
              ))}
            </div>
          )}
          <textarea
            ref={textareaRef}
            rows={1}
            placeholder="请输入内容"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            onKeyDown={handleKeyDown}
          />
          <div className="composer-actions">
            <button
              className="composer-icon-button"
              type="button"
              aria-label="添加附件"
              title="添加附件"
              onClick={() => attachmentInputRef.current?.click()}
            >
              <ComposerAttachmentIcon />
            </button>
            <input ref={attachmentInputRef} type="file" multiple hidden onChange={(event) => void addAttachments(event)} />
            <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
            {activeTaskId && !clarifyTaskId ? (
              <button className="send-button stop" type="button" aria-label="停止" title="停止" onClick={onCancel}>
                <ComposerStopIcon />
              </button>
            ) : (
              <button className={`send-button${sending ? " loading" : ""}`} type="submit" aria-label="发送" disabled={!canSend}>
                <ComposerSendIcon />
              </button>
            )}
          </div>
        </form>
        )}
      </div>
    </section>
  );
}
