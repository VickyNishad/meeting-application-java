import { useEffect, useState } from 'react';
import { useLocalParticipant, useMediaDeviceSelect, useRoomContext } from '@livekit/components-react';
import {
  ChevronUp,
  Circle,
  Hand,
  Info,
  MessageSquare,
  Mic,
  MicOff,
  MonitorUp,
  MonitorX,
  PhoneOff,
  Shield,
  SmilePlus,
  Square,
  Users,
  Video,
  VideoOff,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuTrigger,
  Popover,
  PopoverContent,
  PopoverTrigger,
} from '@/components/ui/overlays';
import { meetingsApi, roomStateApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import { cn, formatClock } from '@/lib/utils';
import { useMeetingRoom } from './roomContext';
import { REACTION_EMOJI } from './roomData';
import { useLocalRecording } from './useLocalRecording';
import { isMediaDeviceError } from './mediaErrors';
import type { SidePanel } from './RoomView';

const ctrlBtn = 'size-11 rounded-full border-0 [&_svg]:size-5';
const neutral = 'bg-white/10 text-white hover:bg-white/20 hover:text-white';
const off = 'bg-red-600 text-white hover:bg-red-500 hover:text-white';

function DeviceMenu({ kind, label }: { kind: MediaDeviceKind; label: string }) {
  const room = useRoomContext();
  const { devices, activeDeviceId, setActiveMediaDevice } = useMediaDeviceSelect({ kind, room, requestPermissions: false });
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button size="icon" variant="ghost" className="-ml-1 size-7 rounded-full bg-white/10 text-white hover:bg-white/20 hover:text-white" aria-label={`Choose ${label}`}>
          <ChevronUp aria-hidden="true" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent side="top" className="max-w-80">
        <DropdownMenuLabel>{label}</DropdownMenuLabel>
        {devices.length === 0 && <p className="px-2 py-1.5 text-sm text-muted-foreground">No devices found</p>}
        <DropdownMenuRadioGroup
          value={activeDeviceId}
          onValueChange={(id) => {
            setActiveMediaDevice(id).catch((e: unknown) => toast.error(`Could not switch ${label.toLowerCase()}: ${errorMessage(e)}`));
          }}
        >
          {devices.map((d, i) => (
            <DropdownMenuRadioItem key={d.deviceId || i} value={d.deviceId}>
              <span className="truncate">{d.label || `${label} ${i + 1}`}</span>
            </DropdownMenuRadioItem>
          ))}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

function LeaveDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o: boolean) => void }) {
  const room = useRoomContext();
  const { meeting, join, myRole, finish } = useMeetingRoom();
  const [busy, setBusy] = useState<'leave' | 'end' | null>(null);
  const canEnd = myRole === 'HOST' || myRole === 'CO_HOST';

  const leave = async () => {
    setBusy('leave');
    try {
      await meetingsApi.leave(meeting.id, join.participantId, join.waitToken);
    } catch (e) {
      // Still disconnect locally; the server also detects the LiveKit disconnect via webhook.
      toast.error(`Leave was not confirmed by the server: ${errorMessage(e)}`);
    }
    await room.disconnect();
    setBusy(null);
    finish('left');
  };
  const end = async () => {
    setBusy('end');
    try {
      await meetingsApi.end(meeting.id);
      await room.disconnect();
      finish('ended');
    } catch (e) {
      toast.error(errorMessage(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <Dialog open={open} onOpenChange={(o) => !busy && onOpenChange(o)}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{canEnd ? 'Leave or end the meeting?' : 'Leave the meeting?'}</DialogTitle>
          <DialogDescription>
            {canEnd ? 'You can leave and let the meeting continue, or end it for everyone.' : 'You can rejoin later while the meeting is still running.'}
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)} disabled={!!busy}>
            Cancel
          </Button>
          <Button variant={canEnd ? 'secondary' : 'destructive'} onClick={() => void leave()} loading={busy === 'leave'} disabled={!!busy}>
            Leave meeting
          </Button>
          {canEnd && (
            <Button variant="destructive" onClick={() => void end()} loading={busy === 'end'} disabled={!!busy}>
              End for everyone
            </Button>
          )}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function LocalRecordButton({ onChange }: { onChange: (recording: boolean) => void }) {
  const { meeting, join } = useMeetingRoom();
  const rec = useLocalRecording(meeting.id, join.participantId, join.waitToken, meeting.title);
  const [infoOpen, setInfoOpen] = useState(false);
  const recording = rec.state === 'recording';
  useEffect(() => onChange(recording), [recording, onChange]);

  if (!rec.support.supported) {
    const reason = rec.support.reason;
    return (
      <Button
        size="icon"
        variant="ghost"
        className={cn(ctrlBtn, neutral, 'opacity-60')}
        aria-label={`Local recording unavailable: ${reason}`}
        title={reason}
        onClick={() => toast.info(reason)}
      >
        <Circle aria-hidden="true" />
      </Button>
    );
  }

  const ext = rec.support.extension.toUpperCase();
  return (
    <>
      {recording ? (
        <Button className={cn('h-11 rounded-full px-4', off)} onClick={rec.stop} aria-label={`Stop local recording (${formatClock(rec.elapsed)})`}>
          <Square className="fill-current" aria-hidden="true" />
          <span className="font-mono text-xs">REC {formatClock(rec.elapsed)}</span>
        </Button>
      ) : (
        <Button
          size="icon"
          variant="ghost"
          className={cn(ctrlBtn, neutral)}
          aria-label="Record locally to this device"
          title="Record locally"
          loading={rec.state === 'starting' || rec.state === 'saving'}
          onClick={() => setInfoOpen(true)}
        >
          {rec.state === 'idle' && <Circle aria-hidden="true" />}
        </Button>
      )}
      <Dialog open={infoOpen} onOpenChange={setInfoOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Record this meeting to your device</DialogTitle>
            <DialogDescription>Everyone in the meeting will see that you are recording.</DialogDescription>
          </DialogHeader>
          <ul className="grid gap-2 text-sm">
            <li className="flex gap-2">
              <Info className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              Your browser will ask what to capture. Choose <strong>this tab</strong> for the best result.
            </li>
            <li className="flex gap-2">
              <Info className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              Meeting audio (your microphone and other participants) is mixed in automatically. Capturing additional system or tab audio depends on your browser and OS — Chromium browsers support tab audio; Firefox and Safari generally do not.
            </li>
            <li className="flex gap-2">
              <Info className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden="true" />
              The file ({ext}) is kept in memory and downloaded when you stop. Very long recordings are stopped automatically at 2 GB.
            </li>
          </ul>
          {rec.lastError && (
            <p role="alert" className="text-sm text-destructive">
              Last attempt: {rec.lastError}
            </p>
          )}
          <DialogFooter>
            <Button variant="outline" onClick={() => setInfoOpen(false)}>
              Cancel
            </Button>
            <Button
              onClick={() => {
                setInfoOpen(false);
                void rec.start();
              }}
            >
              <Circle className="fill-current" aria-hidden="true" /> Start recording
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}

export function ControlBar({
  panel,
  togglePanel,
  unreadChat,
  onReaction,
  onLocalRecordingChange,
}: {
  panel: SidePanel;
  togglePanel: (p: Exclude<SidePanel, null>) => void;
  unreadChat: number;
  onReaction: (emoji: string) => void;
  onLocalRecordingChange: (recording: boolean) => void;
}) {
  const { meeting, isModerator, screenSharePolicy, raisedHands, setHand, join, waitingCount } = useMeetingRoom();
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } = useLocalParticipant();
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [reactOpen, setReactOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const audioOnly = meeting.mediaMode === 'AUDIO_ONLY';
  const handRaised = !!raisedHands[join.participantId];
  const shareBlocked = screenSharePolicy === 'HOSTS_ONLY' && !isModerator;

  const toggle = async (key: string, fn: () => Promise<unknown>, what: string) => {
    setBusy(key);
    try {
      await fn();
    } catch (e) {
      // Mic/camera device failures are already shown by the room's onMediaDeviceFailure notice.
      if (key !== 'share' && isMediaDeviceError(e)) return;
      const name = e instanceof Error ? e.name : '';
      if (name === 'NotAllowedError') toast.error(`Permission to use the ${what} was denied.`);
      else toast.error(`Could not toggle ${what}: ${errorMessage(e)}`);
    } finally {
      setBusy(null);
    }
  };

  // Hands and reactions go through the server, which decides who sees them (the stage only, in a webinar).
  const raiseHand = async () => {
    const next = !handRaised;
    try {
      await roomStateApi.hand(meeting.id, join.participantId, next, join.waitToken);
      setHand(join.participantId, next);
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };

  const react = async (emoji: string) => {
    setReactOpen(false);
    try {
      await roomStateApi.reaction(meeting.id, join.participantId, emoji, join.waitToken);
      onReaction(emoji);
    } catch (e) {
      toast.error(errorMessage(e));
    }
  };

  return (
    <footer className="flex flex-wrap items-center justify-center gap-2 border-t border-white/10 bg-neutral-950 px-2 py-3 sm:gap-3" aria-label="Meeting controls">
      <div className="flex items-center">
        <Button
          size="icon"
          variant="ghost"
          className={cn(ctrlBtn, isMicrophoneEnabled ? neutral : off)}
          aria-pressed={!isMicrophoneEnabled}
          aria-label={isMicrophoneEnabled ? 'Mute microphone' : 'Unmute microphone'}
          loading={busy === 'mic'}
          onClick={() => void toggle('mic', () => localParticipant.setMicrophoneEnabled(!isMicrophoneEnabled), 'microphone')}
        >
          {busy !== 'mic' && (isMicrophoneEnabled ? <Mic aria-hidden="true" /> : <MicOff aria-hidden="true" />)}
        </Button>
        <DeviceMenu kind="audioinput" label="Microphone" />
      </div>
      {!audioOnly && (
        <div className="flex items-center">
          <Button
            size="icon"
            variant="ghost"
            className={cn(ctrlBtn, isCameraEnabled ? neutral : off)}
            aria-pressed={!isCameraEnabled}
            aria-label={isCameraEnabled ? 'Turn camera off' : 'Turn camera on'}
            loading={busy === 'cam'}
            onClick={() => void toggle('cam', () => localParticipant.setCameraEnabled(!isCameraEnabled), 'camera')}
          >
            {busy !== 'cam' && (isCameraEnabled ? <Video aria-hidden="true" /> : <VideoOff aria-hidden="true" />)}
          </Button>
          <DeviceMenu kind="videoinput" label="Camera" />
        </div>
      )}
      {!audioOnly && (
        <Button
          size="icon"
          variant="ghost"
          className={cn(ctrlBtn, isScreenShareEnabled ? 'bg-primary text-primary-foreground hover:bg-primary/90' : neutral)}
          aria-pressed={isScreenShareEnabled}
          aria-label={shareBlocked ? 'Screen sharing is limited to hosts' : isScreenShareEnabled ? 'Stop sharing screen' : 'Share screen'}
          title={shareBlocked ? 'Only hosts and co-hosts can share' : undefined}
          disabled={(shareBlocked && !isScreenShareEnabled) || busy === 'share'}
          onClick={() => void toggle('share', () => localParticipant.setScreenShareEnabled(!isScreenShareEnabled, { audio: true, selfBrowserSurface: 'exclude' }), 'screen sharing')}
        >
          {isScreenShareEnabled ? <MonitorX aria-hidden="true" /> : <MonitorUp aria-hidden="true" />}
        </Button>
      )}
      <Button
        size="icon"
        variant="ghost"
        className={cn(ctrlBtn, handRaised ? 'bg-warning text-black hover:bg-warning/90' : neutral)}
        aria-pressed={handRaised}
        aria-label={handRaised ? 'Lower hand' : 'Raise hand'}
        onClick={() => void raiseHand()}
      >
        <Hand aria-hidden="true" />
      </Button>
      <Popover open={reactOpen} onOpenChange={setReactOpen}>
        <PopoverTrigger asChild>
          <Button size="icon" variant="ghost" className={cn(ctrlBtn, neutral)} aria-label="Send a reaction">
            <SmilePlus aria-hidden="true" />
          </Button>
        </PopoverTrigger>
        <PopoverContent side="top" className="flex gap-1 p-2">
          {REACTION_EMOJI.map((e) => (
            <button key={e} className="rounded-md p-1.5 text-2xl hover:bg-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring" aria-label={`React ${e}`} onClick={() => void react(e)}>
              {e}
            </button>
          ))}
        </PopoverContent>
      </Popover>
      <LocalRecordButton onChange={onLocalRecordingChange} />

      <div className="mx-1 hidden h-8 w-px bg-white/10 sm:block" aria-hidden="true" />

      <Button size="icon" variant="ghost" className={cn(ctrlBtn, 'relative', panel === 'chat' ? 'bg-primary text-primary-foreground hover:bg-primary/90' : neutral)} aria-pressed={panel === 'chat'} aria-label={unreadChat ? `Chat, ${unreadChat} unread` : 'Chat'} onClick={() => togglePanel('chat')}>
        <MessageSquare aria-hidden="true" />
        {unreadChat > 0 && <span className="absolute -right-0.5 -top-0.5 flex min-w-5 items-center justify-center rounded-full bg-red-600 px-1 text-[10px] font-bold">{unreadChat}</span>}
      </Button>
      <Button size="icon" variant="ghost" className={cn(ctrlBtn, panel === 'participants' ? 'bg-primary text-primary-foreground hover:bg-primary/90' : neutral)} aria-pressed={panel === 'participants'} aria-label="Participants" onClick={() => togglePanel('participants')}>
        <Users aria-hidden="true" />
      </Button>
      {isModerator && (
        <Button size="icon" variant="ghost" className={cn(ctrlBtn, 'relative', panel === 'moderation' ? 'bg-primary text-primary-foreground hover:bg-primary/90' : neutral)} aria-pressed={panel === 'moderation'} aria-label={waitingCount ? `Host controls, ${waitingCount} waiting` : 'Host controls'} onClick={() => togglePanel('moderation')}>
          <Shield aria-hidden="true" />
          {waitingCount > 0 && <span className="absolute -right-0.5 -top-0.5 flex min-w-5 items-center justify-center rounded-full bg-warning px-1 text-[10px] font-bold text-black">{waitingCount}</span>}
        </Button>
      )}
      <Button className="h-11 rounded-full bg-red-600 px-5 text-white hover:bg-red-500" onClick={() => setLeaveOpen(true)} aria-label="Leave meeting">
        <PhoneOff aria-hidden="true" /> <span className="hidden sm:inline">Leave</span>
      </Button>
      <LeaveDialog open={leaveOpen} onOpenChange={setLeaveOpen} />
    </footer>
  );
}
