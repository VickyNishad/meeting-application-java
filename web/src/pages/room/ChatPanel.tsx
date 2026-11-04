import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Download, FileUp, Loader2, MessageSquare, Paperclip, Send } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Avatar, Input } from '@/components/ui/primitives';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/overlays';
import { chatApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import { formatTime } from '@/lib/time';
import { cn, formatBytes } from '@/lib/utils';
import { useMeetingRoom } from './roomContext';
import { PanelChrome, darkInput } from './PanelChrome';
import type { MeetingChat } from './useMeetingChat';

function Messages({ chat }: { chat: MeetingChat }) {
  const { join, chatEnabled, isModerator, stageOnly } = useMeetingRoom();
  const canSend = chatEnabled || isModerator;
  const toStageOnly = stageOnly && !isModerator;
  const [text, setText] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const endRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    endRef.current?.scrollIntoView({ block: 'end' });
  }, [chat.messages.length]);

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    const t = text.trim();
    if (!t || !canSend) return;
    setSending(true);
    setError(null);
    try {
      await chat.send(t);
      setText('');
    } catch (err) {
      setError(errorMessage(err));
    } finally {
      setSending(false);
    }
  };

  return (
    <div className="flex h-full min-h-0 flex-col">
      <div className="min-h-0 flex-1 overflow-y-auto p-4" role="log" aria-live="polite" aria-label="Chat messages">
        {chat.loading && <Loader2 className="mx-auto size-5 animate-spin text-neutral-400" aria-label="Loading messages" />}
        {chat.error != null && (
          <div className="text-sm text-red-300" role="alert">
            {errorMessage(chat.error)}{' '}
            <button className="underline" onClick={chat.reload}>
              Retry
            </button>
          </div>
        )}
        {!chat.loading && chat.messages.length === 0 && !chat.error && (
          <div className="flex flex-col items-center gap-2 py-10 text-center text-sm text-neutral-400">
            <MessageSquare className="size-8 text-neutral-600" aria-hidden="true" />
            No messages yet. Say hi 👋
          </div>
        )}
        {chat.messages.map((m, i) => {
          const mine = m.participantId === join.participantId;
          // Consecutive messages from one sender form a group: name, avatar and time once, at its start.
          const prev = chat.messages[i - 1];
          const first = !prev || prev.participantId !== m.participantId || prev.audience !== m.audience;
          return (
            <div key={m.id} className={cn('flex gap-2', mine ? 'justify-end' : 'justify-start', first ? 'mt-3 first:mt-0' : 'mt-1')}>
              {!mine && (first ? <Avatar name={m.senderName} className="mt-5 size-7 bg-white/10 text-[10px] text-neutral-100" /> : <span className="w-7 shrink-0" aria-hidden="true" />)}
              <div className={cn('flex min-w-0 max-w-[80%] flex-col', mine ? 'items-end' : 'items-start')}>
                {first && (
                  <span className="mb-1 flex items-center gap-1.5 text-[11px] text-neutral-400">
                    <span className="font-semibold text-neutral-200">{mine ? 'You' : m.senderName}</span>
                    {formatTime(m.sentAt)}
                    {m.audience === 'STAGE' && <span className="rounded bg-white/10 px-1 text-neutral-300">to hosts</span>}
                  </span>
                )}
                <p
                  className={cn(
                    'max-w-full whitespace-pre-wrap rounded-2xl px-3 py-2 text-sm leading-relaxed [overflow-wrap:anywhere]',
                    mine ? 'rounded-br-md bg-primary text-primary-foreground' : 'rounded-bl-md bg-white/10 text-neutral-100',
                  )}
                  title={first ? undefined : formatTime(m.sentAt)}
                >
                  {m.text}
                </p>
              </div>
            </div>
          );
        })}
        <div ref={endRef} />
      </div>
      <form onSubmit={(e) => void submit(e)} className="border-t border-white/10 p-3">
        {error && (
          <p role="alert" className="mb-2 text-xs text-red-300">
            {error}
          </p>
        )}
        {!chatEnabled && (
          <p className="mb-2 text-xs text-neutral-400" role="status">
            {isModerator ? 'Chat is disabled for participants. Hosts can still send messages.' : 'The host has disabled chat.'}
          </p>
        )}
        {chatEnabled && toStageOnly && (
          <p className="mb-2 text-xs text-neutral-400">Webinar: your messages go to the host and co-hosts only.</p>
        )}
        <div className="flex gap-2">
          <label htmlFor="chat-input" className="sr-only">
            Message
          </label>
          <Input id="chat-input" className={darkInput} value={text} maxLength={4000} placeholder={canSend ? 'Send a message' : 'Chat is disabled'} disabled={!canSend} onChange={(e) => setText(e.target.value)} autoComplete="off" />
          <Button type="submit" size="icon" className="size-10 shrink-0 rounded-lg" aria-label="Send message" loading={sending} disabled={!canSend || !text.trim()}>
            {!sending && <Send aria-hidden="true" />}
          </Button>
        </div>
      </form>
    </div>
  );
}

function Files() {
  const { meeting, join } = useMeetingRoom();
  const qc = useQueryClient();
  const fileRef = useRef<HTMLInputElement>(null);
  const q = useQuery({ queryKey: ['meeting-files', meeting.id], queryFn: () => chatApi.files(meeting.id, join.waitToken), refetchInterval: 15_000 });
  const upload = useMutation({
    mutationFn: (file: File) => chatApi.upload(meeting.id, join.participantId, file, join.waitToken),
    onSuccess: () => {
      toast.success('File shared');
      void qc.invalidateQueries({ queryKey: ['meeting-files', meeting.id] });
    },
    onError: (e) => toast.error(errorMessage(e)),
  });
  const download = async (fileId: string) => {
    try {
      const { url } = await chatApi.fileUrl(meeting.id, fileId, join.waitToken);
      window.open(url, '_blank', 'noopener');
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };
  return (
    <div className="space-y-3 p-4">
      <input
        ref={fileRef}
        type="file"
        className="sr-only"
        aria-label="Choose a file to share"
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) {
            if (f.size > 25 * 1024 * 1024) toast.error('Files must be 25 MB or smaller.');
            else upload.mutate(f);
          }
          e.target.value = '';
        }}
      />
      <Button size="sm" className="w-full" onClick={() => fileRef.current?.click()} loading={upload.isPending}>
        <FileUp aria-hidden="true" /> Share a file (max 25 MB)
      </Button>
      {q.isPending && <Loader2 className="mx-auto size-5 animate-spin text-neutral-400" aria-label="Loading files" />}
      {q.isError && <p className="text-sm text-red-300">{errorMessage(q.error)}</p>}
      {q.data?.length === 0 && <p className="text-center text-sm text-neutral-400">No files shared yet.</p>}
      <ul className="space-y-2">
        {q.data?.map((f) => (
          <li key={f.id} className="flex items-center gap-2 rounded-lg bg-white/5 p-2 text-sm">
            <Paperclip className="size-4 shrink-0 text-neutral-400" aria-hidden="true" />
            <div className="min-w-0 flex-1">
              <p className="truncate">{f.fileName}</p>
              <p className="text-[11px] text-neutral-400">
                {formatBytes(f.sizeBytes)} · {f.uploadedBy}
              </p>
            </div>
            <Button size="icon" variant="ghost" className="text-neutral-200 hover:bg-white/10" aria-label={`Download ${f.fileName}`} onClick={() => void download(f.id)}>
              <Download aria-hidden="true" />
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}

export function ChatPanel({ chat, onClose }: { chat: MeetingChat; onClose: () => void }) {
  return (
    <PanelChrome title="Chat" icon={<MessageSquare aria-hidden="true" />} onClose={onClose}>
      <Tabs defaultValue="messages" className="flex h-full flex-col">
        <TabsList className="mx-4 mt-3 grid h-9 grid-cols-2 rounded-lg bg-white/5 p-1 text-neutral-300">
          <TabsTrigger value="messages" className="data-[state=active]:bg-neutral-700 data-[state=active]:text-white">
            Messages
          </TabsTrigger>
          <TabsTrigger value="files" className="data-[state=active]:bg-neutral-700 data-[state=active]:text-white">
            Files
          </TabsTrigger>
        </TabsList>
        <TabsContent value="messages" className="mt-0 min-h-0 flex-1">
          <Messages chat={chat} />
        </TabsContent>
        <TabsContent value="files" className="mt-0 min-h-0 flex-1 overflow-y-auto">
          <Files />
        </TabsContent>
      </Tabs>
    </PanelChrome>
  );
}
