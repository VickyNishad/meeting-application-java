import { useCallback, useEffect, useRef, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useDataChannel, useRoomContext } from '@livekit/components-react';
import { RoomEvent } from 'livekit-client';
import { chatApi } from '@/lib/api/endpoints';
import type { ChatMessage } from '@/lib/api/types';
import { TOPIC, decodePacket, isFromServer, mergeChat, parseChat } from './roomData';

export interface MeetingChat {
  messages: ChatMessage[];
  loading: boolean;
  error: unknown;
  reload: () => void;
  send: (text: string) => Promise<void>;
}

/**
 * History via GET /chat, new messages via the server-relayed `chat` data topic,
 * sending via POST /chat (the server persists then relays to everyone, including us — deduped by id).
 * History is re-fetched on every mount (Reconnect/Rejoin remounts the room) and after a LiveKit reconnect,
 * since packets relayed while we were away are missed; everything is merged by message id.
 */
export function useMeetingChat(meetingId: string, participantId: string, waitToken: string, onIncoming?: (m: ChatMessage) => void): MeetingChat {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const onIncomingRef = useRef(onIncoming);
  useEffect(() => {
    onIncomingRef.current = onIncoming;
  }, [onIncoming]);

  const room = useRoomContext();
  const history = useQuery({
    queryKey: ['chat', meetingId],
    queryFn: () => chatApi.history(meetingId, waitToken, undefined, 100),
    // Never refetch on a timer, but always on mount: a cached snapshot from before a reconnect is stale.
    staleTime: Infinity,
    refetchOnMount: 'always',
  });
  const { refetch } = history;

  useEffect(() => {
    const onReconnected = () => void refetch();
    room.on(RoomEvent.Reconnected, onReconnected);
    return () => {
      room.off(RoomEvent.Reconnected, onReconnected);
    };
  }, [room, refetch]);

  useEffect(() => {
    if (history.data) setMessages((cur) => mergeChat(cur, history.data));
  }, [history.data]);

  useDataChannel(TOPIC.chat, (msg) => {
    if (!isFromServer(msg.from)) return;
    const m = parseChat(decodePacket(msg.payload));
    if (!m) return;
    setMessages((cur) => mergeChat(cur, [m]));
    if (m.participantId !== participantId) onIncomingRef.current?.(m);
  });

  const send = useCallback(
    async (text: string) => {
      const saved = await chatApi.send(meetingId, participantId, text, waitToken);
      if (saved?.id) setMessages((cur) => mergeChat(cur, [saved]));
    },
    [meetingId, participantId, waitToken],
  );

  return {
    messages,
    loading: history.isPending,
    error: history.error,
    reload: () => void history.refetch(),
    send,
  };
}
