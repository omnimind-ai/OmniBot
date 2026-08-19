import { useEffect, useRef, useState } from "react";
import { eventsUrl } from "../api";
import type {
  ConnectionStatus,
  RealtimeEventData,
  RealtimeEventName,
} from "../types";

const EVENT_NAMES: RealtimeEventName[] = [
  "conversation_created",
  "conversation_updated",
  "conversation_deleted",
  "messages_replaced",
  "acp_event",
  "chat_task_event",
  "browser_snapshot_updated",
  "workspace_changed",
];

export function useRealtime(
  enabled: boolean,
  onEvent: (name: RealtimeEventName, data: RealtimeEventData) => void,
): ConnectionStatus {
  const handlerRef = useRef(onEvent);
  const [status, setStatus] = useState<ConnectionStatus>("connecting");

  useEffect(() => {
    handlerRef.current = onEvent;
  }, [onEvent]);

  useEffect(() => {
    if (!enabled) {
      setStatus("connecting");
      return undefined;
    }
    const source = new EventSource(eventsUrl());
    setStatus("connecting");
    source.onopen = () => setStatus("online");
    source.onerror = () => setStatus("offline");

    const listeners = EVENT_NAMES.map((name) => {
      const listener = (event: MessageEvent<string>) => {
        try {
          handlerRef.current(name, JSON.parse(event.data) as RealtimeEventData);
        } catch {
          // Ignore malformed server events and keep the realtime channel alive.
        }
      };
      source.addEventListener(name, listener as EventListener);
      return [name, listener] as const;
    });

    return () => {
      listeners.forEach(([name, listener]) => {
        source.removeEventListener(name, listener as EventListener);
      });
      source.close();
    };
  }, [enabled]);

  return status;
}
