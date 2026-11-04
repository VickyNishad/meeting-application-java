import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Check, Circle, Loader2, MicOff, Shield, Square, UserCheck, X } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Avatar, Label, NativeSelect, Switch } from '@/components/ui/primitives';
import { moderationApi, recordingsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { MediaKindKey, Recording } from '@/lib/api/types';
import { isRoomRecording, recordingSubject } from '@/lib/recordings';
import { formatTime } from '@/lib/time';
import { useMeetingRoom } from './roomContext';
import { PanelChrome, PanelSection, SettingRow } from './PanelChrome';

const Section = PanelSection;

function WaitingList() {
  const { meeting } = useMeetingRoom();
  const qc = useQueryClient();
  // Kept current by `waiting-room` signals; polling is a backstop while the panel is open and visible.
  const q = useQuery({
    queryKey: ['waiting', meeting.id],
    queryFn: () => moderationApi.waiting(meeting.id),
    refetchInterval: 5000,
    refetchIntervalInBackground: false,
  });
  const refresh = () => qc.invalidateQueries({ queryKey: ['waiting', meeting.id] });
  const onErr = (e: unknown) => toast.error(errorMessage(e));
  const admit = useMutation({ mutationFn: (pid: string) => moderationApi.admit(meeting.id, pid), onSuccess: refresh, onError: onErr });
  const deny = useMutation({ mutationFn: (pid: string) => moderationApi.deny(meeting.id, pid), onSuccess: refresh, onError: onErr });
  const admitAll = useMutation({
    mutationFn: () => moderationApi.admitAll(meeting.id),
    onSuccess: () => {
      toast.success('Everyone was admitted');
      void refresh();
    },
    onError: onErr,
  });

  const waiting = q.data ?? [];
  return (
    <Section title={`Waiting room (${waiting.length})`} description="People asking to join wait here until a host admits them.">
      {q.isPending && <Loader2 className="size-4 animate-spin text-neutral-400" aria-label="Loading waiting room" />}
      {q.isError && <p className="text-sm text-red-300">{errorMessage(q.error)}</p>}
      {q.data && waiting.length === 0 && <p className="rounded-lg bg-white/[0.03] px-3 py-2.5 text-center text-sm text-neutral-400">No one is waiting.</p>}
      {waiting.length > 0 && (
        <>
          <ul className="mb-3 space-y-1.5">
            {waiting.map((w) => (
              <li key={w.id} className="flex items-center gap-2.5 rounded-lg border border-white/10 bg-white/5 py-1.5 pl-2 pr-1.5 text-sm">
                <Avatar name={w.displayName} className="size-7 bg-white/10 text-[10px] text-neutral-100" />
                <span className="min-w-0 flex-1 truncate font-medium">
                  {w.displayName}
                  {w.role === 'GUEST' && <span className="ml-1 text-xs font-normal text-neutral-400">(guest)</span>}
                </span>
                <Button size="icon" variant="ghost" className="size-8 rounded-lg text-success hover:bg-success/15 hover:text-success" aria-label={`Admit ${w.displayName}`} onClick={() => admit.mutate(w.id)} disabled={admit.isPending}>
                  <Check aria-hidden="true" />
                </Button>
                <Button size="icon" variant="ghost" className="size-8 rounded-lg text-red-400 hover:bg-red-500/15 hover:text-red-300" aria-label={`Deny ${w.displayName}`} onClick={() => deny.mutate(w.id)} disabled={deny.isPending}>
                  <X aria-hidden="true" />
                </Button>
              </li>
            ))}
          </ul>
          <Button size="sm" className="w-full" onClick={() => admitAll.mutate()} loading={admitAll.isPending}>
            <UserCheck aria-hidden="true" /> Admit all
          </Button>
        </>
      )}
    </Section>
  );
}

function CloudRecording() {
  const { meeting, join, cloudRecording, setCloudRecording, stageOnly } = useMeetingRoom();
  const [layout, setLayout] = useState<'grid' | 'speaker'>('speaker');
  const [busy, setBusy] = useState(false);
  const q = useQuery({ queryKey: ['meeting', meeting.id, 'recordings'], queryFn: () => recordingsApi.forMeeting(meeting.id, join.waitToken), refetchInterval: 10_000 });
  const active = q.data?.find((r) => isRoomRecording(r) && (r.status === 'ACTIVE' || r.status === 'STARTING' || r.status === 'STOPPING'));
  const isOn = cloudRecording || !!active;

  const start = async () => {
    setBusy(true);
    try {
      await recordingsApi.start(meeting.id, { layout, audioOnly: meeting.mediaMode === 'AUDIO_ONLY' });
      setCloudRecording(true);
      toast.success('Cloud recording is starting');
      void q.refetch();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };
  const stop = async () => {
    setBusy(true);
    try {
      let rec = active;
      if (!rec) rec = (await q.refetch()).data?.find((r) => isRoomRecording(r) && (r.status === 'ACTIVE' || r.status === 'STARTING'));
      if (!rec) throw new Error('No active cloud recording was found for this meeting.');
      await recordingsApi.stop(meeting.id, rec.id);
      setCloudRecording(false);
      toast.success('Cloud recording stopped — it will appear in the library after processing.');
      void q.refetch();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Section title="Cloud recording" description="Recorded on the server and saved to the recordings library.">
      {isOn ? (
        <div className="grid gap-2">
          <p className="flex items-center gap-2 text-sm">
            <Circle className="size-2.5 animate-pulse fill-red-500 text-red-500" aria-hidden="true" />
            Recording{active?.status ? ` (${active.status.toLowerCase()})` : ''}
            {active?.startedAt && <span className="text-neutral-400">since {formatTime(active.startedAt)}</span>}
          </p>
          <Button size="sm" variant="destructive" onClick={() => void stop()} loading={busy}>
            <Square aria-hidden="true" /> Stop recording
          </Button>
        </div>
      ) : (
        <div className="grid gap-2">
          {meeting.mediaMode === 'VIDEO' && (
            <div className="grid gap-1.5">
              <Label htmlFor="rec-layout" className="text-neutral-300">
                Layout
              </Label>
              <NativeSelect id="rec-layout" value={layout} onChange={(e) => setLayout(e.target.value as 'grid' | 'speaker')} className="border-white/15 bg-neutral-800">
                <option value="grid">Gallery</option>
                <option value="speaker">Active speaker</option>
              </NativeSelect>
            </div>
          )}
          <Button size="sm" onClick={() => void start()} loading={busy}>
            <Circle className="fill-current" aria-hidden="true" /> Start cloud recording
          </Button>
          <p className="text-xs text-neutral-400">
            Everyone in the meeting sees a recording indicator.
            {stageOnly && ' Webinar: the recording shows the host and co-hosts only.'}
          </p>
        </div>
      )}
      <PersonRecordings recordings={q.data ?? []} onChanged={() => void q.refetch()} />
    </Section>
  );
}

/**
 * Older per-person recordings (auto-record used to record each participant separately) that are still running:
 * the host can stop them one by one. Nothing is shown once there are none; new recordings are room recordings only.
 */
function PersonRecordings({ recordings, onChanged }: { recordings: Recording[]; onChanged: () => void }) {
  const { meeting } = useMeetingRoom();
  const [stopping, setStopping] = useState<string | null>(null);
  const running = recordings.filter((r) => !isRoomRecording(r) && (r.status === 'ACTIVE' || r.status === 'STARTING'));
  if (running.length === 0) return null;

  const stop = async (r: Recording) => {
    setStopping(r.id);
    try {
      await recordingsApi.stop(meeting.id, r.id);
      toast.success(`Stopped recording ${recordingSubject(r)}`);
      onChanged();
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setStopping(null);
    }
  };

  return (
    <div className="mt-3 grid gap-2">
      <p className="text-xs font-medium text-neutral-300">Individual recordings still running</p>
      <ul className="grid gap-1.5">
        {running.map((r) => (
          <li key={r.id} className="flex items-center justify-between gap-2 text-sm">
            <span className="flex min-w-0 items-center gap-2">
              <Circle className="size-2 shrink-0 animate-pulse fill-red-500 text-red-500" aria-hidden="true" />
              <span className="truncate">{recordingSubject(r)}</span>
            </span>
            <Button size="sm" variant="secondary" onClick={() => void stop(r)} loading={stopping === r.id} aria-label={`Stop recording ${recordingSubject(r)}`}>
              <Square aria-hidden="true" /> Stop
            </Button>
          </li>
        ))}
      </ul>
    </div>
  );
}

const MEDIA_SWITCHES: { key: MediaKindKey; label: string; hint: string; video: boolean }[] = [
  { key: 'microphone', label: 'Allow microphones', hint: 'Off: participants are muted and cannot unmute.', video: false },
  { key: 'camera', label: 'Allow cameras', hint: 'Off: participants’ cameras are turned off and stay off.', video: true },
  { key: 'screenShare', label: 'Allow screen sharing', hint: 'Off: only hosts and co-hosts can share.', video: true },
];

/**
 * Meeting-wide media permissions for participants. The host changes them (the server enforces them on the media
 * server and refuses anyone else); co-hosts see them. Per-participant exceptions are in the participants list.
 */
function MediaPermissionsSection() {
  const { meeting, myRole, mediaPermissions, setMediaPermissions } = useMeetingRoom();
  const [pending, setPending] = useState<MediaKindKey | null>(null);
  const isHost = myRole === 'HOST';
  const change = async (key: MediaKindKey, allowed: boolean, label: string) => {
    setPending(key);
    try {
      setMediaPermissions(await moderationApi.mediaPermissions(meeting.id, { [key]: allowed ? 'ALLOWED' : 'RESTRICTED' }));
      const what = label.replace('Allow ', '');
      toast.success(`${what.charAt(0).toUpperCase()}${what.slice(1)}: ${allowed ? 'allowed for everyone' : 'restricted for participants'}`);
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setPending(null);
    }
  };
  return (
    <Section title="Media permissions" description="What participants may use. Hosts and co-hosts always can.">
      <div className="grid gap-4">
        {MEDIA_SWITCHES.filter((s) => !s.video || meeting.mediaMode === 'VIDEO').map((s) => (
          <SettingRow key={s.key} id={`mod-media-${s.key}`} label={s.label} hint={s.hint}>
            <Switch
              id={`mod-media-${s.key}`}
              checked={mediaPermissions[s.key] === 'ALLOWED'}
              disabled={!isHost || pending === s.key}
              onCheckedChange={(v) => void change(s.key, v, s.label)}
            />
          </SettingRow>
        ))}
        <p className="rounded-lg bg-white/[0.03] px-3 py-2 text-xs leading-relaxed text-neutral-400">
          {isHost
            ? 'Allow or restrict individual participants from their menu in the participants list.'
            : 'Only the host can change media permissions.'}
        </p>
      </div>
    </Section>
  );
}

export function ModerationPanel({ onClose }: { onClose: () => void }) {
  const { meeting, locked, setLocked, waitingRoomEnabled, setWaitingRoomEnabled, chatEnabled, setChatEnabled } = useMeetingRoom();
  const [pending, setPending] = useState<string | null>(null);

  const run = async (key: string, fn: () => Promise<unknown>, success: string) => {
    setPending(key);
    try {
      await fn();
      toast.success(success);
      return true;
    } catch (e) {
      toast.error(errorMessage(e));
      return false;
    } finally {
      setPending(null);
    }
  };

  return (
    <PanelChrome title="Host controls" icon={<Shield aria-hidden="true" />} onClose={onClose}>
      <div className="space-y-3 p-3">
        <WaitingList />
        <Section title="Audio" description="Hosts and co-hosts are not muted. Participants may unmute again unless microphones are restricted below.">
          <Button size="sm" variant="ghost" className="h-9 w-full rounded-lg border border-white/15 bg-white/5 text-neutral-100 hover:bg-white/10 hover:text-white" loading={pending === 'mute-all'} onClick={() => void run('mute-all', () => moderationApi.muteAll(meeting.id), 'Everyone was muted')}>
            <MicOff aria-hidden="true" /> Mute everyone
          </Button>
        </Section>
        <MediaPermissionsSection />
        <Section title="Meeting settings">
          <div className="grid gap-4">
            <SettingRow id="mod-lock" label="Lock meeting" hint="New people cannot join until you unlock it.">
              <Switch
                id="mod-lock"
                checked={locked}
                disabled={pending === 'lock'}
                onCheckedChange={(v) => void run('lock', () => moderationApi.lock(meeting.id, v), v ? 'Meeting locked' : 'Meeting unlocked').then((ok) => ok && setLocked(v))}
              />
            </SettingRow>
            <SettingRow id="mod-waiting" label="Waiting room" hint="New people wait until a host admits them.">
              <Switch
                id="mod-waiting"
                checked={waitingRoomEnabled}
                disabled={pending === 'waiting'}
                onCheckedChange={(v) =>
                  void run('waiting', () => moderationApi.settings(meeting.id, { waitingRoomEnabled: v }), v ? 'Waiting room on' : 'Waiting room off').then((ok) => ok && setWaitingRoomEnabled(v))
                }
              />
            </SettingRow>
            <SettingRow id="mod-chat" label="Allow chat" hint="When off, participants cannot send chat messages.">
              <Switch
                id="mod-chat"
                checked={chatEnabled}
                disabled={pending === 'chat'}
                onCheckedChange={(v) => void run('chat', () => moderationApi.settings(meeting.id, { chatEnabled: v }), v ? 'Chat enabled' : 'Chat disabled').then((ok) => ok && setChatEnabled(v))}
              />
            </SettingRow>
          </div>
        </Section>
        <CloudRecording />
      </div>
    </PanelChrome>
  );
}
