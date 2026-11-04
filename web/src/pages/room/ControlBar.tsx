import { forwardRef, useContext, useEffect, useRef, useState, type ReactNode } from 'react';
import { useConnectionState, useLocalParticipant, useLocalParticipantPermissions, useMediaDeviceSelect, useRoomContext } from '@livekit/components-react';
import { ConnectionState } from 'livekit-client';
import {
  ChevronUp,
  Circle,
  Disc,
  Hand,
  Info,
  Lock,
  MessageSquare,
  Mic,
  MicOff,
  MonitorUp,
  MonitorX,
  MoreHorizontal,
  PhoneOff,
  Shield,
  SmilePlus,
  Square,
  Users,
  Video,
  VideoOff,
} from 'lucide-react';
import { toast } from 'sonner';
import { Button, type ButtonProps } from '@/components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
  Popover,
  PopoverContent,
  PopoverTrigger,
  Tooltip,
  TooltipProvider,
} from '@/components/ui/overlays';
import { meetingsApi, roomStateApi } from '@/lib/api/endpoints';
import { isNoiseFilterSupported } from '@/lib/audio/noiseFilter';
import { useNoiseReduction } from '@/lib/audio/noiseReductionPref';
import { errorMessage } from '@/lib/api/problem';
import { cn, formatClock } from '@/lib/utils';
import { useMeetingRoom } from './roomContext';
import { REACTION_EMOJI } from './roomData';
import { useLocalRecording } from './useLocalRecording';
import { isMediaDeviceError } from './mediaErrors';
import { isPublishPermissionError, restrictedLabel, useCurrentRights, useMediaRightNotices } from './mediaRights';
import { StageRoomContext } from './useAudienceRoom';
import type { SidePanel } from './RoomView';

/**
 * Control states: neutral; off (muted / camera off, red-tinted so it does not compete with Leave); active (its panel
 * is open); on (screen share running); warn (hand raised).
 */
type Tone = 'neutral' | 'off' | 'active' | 'on' | 'warn';
const TONE: Record<Tone, string> = {
  neutral: 'border-white/10 bg-neutral-800/90 text-neutral-100 hover:bg-neutral-700 hover:text-white',
  off: 'border-red-500/40 bg-red-500/15 text-red-300 hover:bg-red-500/25 hover:text-red-200',
  active: 'border-primary/60 bg-primary/15 text-sky-200 hover:bg-primary/25 hover:text-sky-100',
  on: 'border-primary bg-primary text-primary-foreground hover:bg-primary/90 hover:text-primary-foreground',
  warn: 'border-warning/50 bg-warning/15 text-warning hover:bg-warning/25 hover:text-warning',
};
/** One height for every control (and the Leave button), so the bar lines up. */
const CTRL_H = 'h-12 xl:h-14';

type CtrlButtonProps = Omit<ButtonProps, 'children'> & {
  icon: ReactNode;
  /** Short visible label under the icon (wide screens); the accessible name (aria-label) starts with or contains it. */
  label: string;
  tone?: Tone;
  /** Restricted by the host: a lock on the corner. */
  locked?: boolean;
  /**
   * Cannot be used now (restricted by the host): stays focusable and keeps its tooltip, announced as unavailable
   * (aria-disabled), and does nothing when activated.
   */
  blocked?: boolean;
  /** Count bubble (unread chat, people waiting). */
  badge?: ReactNode;
  /** Left half of a split button (a device chevron follows). */
  joined?: boolean;
};

const CtrlButton = forwardRef<HTMLButtonElement, CtrlButtonProps>(
  ({ icon, label, tone = 'neutral', locked, blocked, badge, joined, loading, className, onClick, ...props }, ref) => (
    <Button
      ref={ref}
      variant="ghost"
      loading={loading}
      aria-disabled={blocked || undefined}
      onClick={blocked ? (e) => e.preventDefault() : onClick}
      className={cn(
        CTRL_H,
        // One width per control from 1280px (labels shown), so the bar never shifts when a label changes.
        'relative w-12 shrink-0 flex-col gap-1 rounded-xl border px-1 text-[11px] font-medium leading-none tracking-tight focus-visible:ring-offset-neutral-950 xl:w-[5.25rem] [&_svg]:size-5',
        TONE[tone],
        blocked && 'cursor-not-allowed opacity-50',
        joined && 'rounded-r-none',
        className,
      )}
      {...props}
    >
      {!loading && icon}
      <span className="hidden max-w-full truncate text-[11px] font-medium leading-tight tracking-tight xl:block">{label}</span>
      {locked && <Lock className="absolute right-1.5 top-1.5 size-3!" aria-hidden="true" />}
      {badge}
    </Button>
  ),
);
CtrlButton.displayName = 'CtrlButton';

function CountBadge({ count, tone = 'red', side = 'right' }: { count: number; tone?: 'red' | 'warning'; side?: 'right' | 'left' }) {
  if (count <= 0) return null;
  return (
    <span
      className={cn(
        'absolute -top-1.5 flex h-5 min-w-5 items-center justify-center rounded-full px-1 text-[10px] font-bold leading-none ring-2 ring-neutral-950',
        side === 'right' ? '-right-1.5' : '-left-1.5',
        tone === 'red' ? 'bg-red-600 text-white' : 'bg-warning text-black',
      )}
      aria-hidden="true"
    >
      {count > 99 ? '99+' : count}
    </span>
  );
}

function Tip({ content, children }: { content: ReactNode; children: ReactNode }) {
  return <Tooltip content={content}>{children}</Tooltip>;
}

function MeetingClock() {
  const [now, setNow] = useState(() => new Date());
  useEffect(() => {
    const id = window.setInterval(() => setNow(new Date()), 15_000);
    return () => window.clearInterval(id);
  }, []);
  return (
    <time className="shrink-0 text-sm font-semibold tabular-nums text-neutral-100" dateTime={now.toISOString()}>
      {now.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
    </time>
  );
}

function NoiseReductionItem() {
  const [on, setOn] = useNoiseReduction();
  if (!isNoiseFilterSupported()) return null;
  return (
    <>
      <DropdownMenuSeparator />
      <DropdownMenuCheckboxItem checked={on} onCheckedChange={setOn} onSelect={(e) => e.preventDefault()}>
        Noise reduction
      </DropdownMenuCheckboxItem>
    </>
  );
}

/** Right half of the microphone / camera split button: device choice. */
function DeviceMenu({ kind, label, tone }: { kind: MediaDeviceKind; label: string; tone: Tone }) {
  const room = useRoomContext();
  const { devices, activeDeviceId, setActiveMediaDevice } = useMediaDeviceSelect({ kind, room, requestPermissions: false });
  return (
    <DropdownMenu>
      <Tip content={`${label} settings`}>
        <DropdownMenuTrigger asChild>
          <Button
            variant="ghost"
            className={cn(CTRL_H, 'w-6 shrink-0 rounded-l-none rounded-r-xl border border-l-0 px-0 focus-visible:ring-offset-neutral-950 lg:w-7 [&_svg]:size-4', TONE[tone])}
            aria-label={`Choose ${label}`}
          >
            <ChevronUp aria-hidden="true" />
          </Button>
        </DropdownMenuTrigger>
      </Tip>
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
        {kind === 'audioinput' && <NoiseReductionItem />}
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
      <Tip content={reason}>
        <CtrlButton
          icon={<Disc aria-hidden="true" />}
          label="Record"
          className="opacity-60"
          aria-label={`Local recording unavailable: ${reason}`}
          onClick={() => toast.info(reason)}
        />
      </Tip>
    );
  }

  const ext = rec.support.extension.toUpperCase();
  return (
    <>
      {recording ? (
        <Tip content="Stop recording and download the file">
          <CtrlButton
            tone="off"
            icon={<Square className="fill-current" aria-hidden="true" />}
            label={`REC ${formatClock(rec.elapsed)}`}
            className="font-mono tabular-nums"
            onClick={rec.stop}
            aria-label={`REC ${formatClock(rec.elapsed)}, stop local recording`}
          />
        </Tip>
      ) : (
        <Tip content="Record this meeting to your device">
          <CtrlButton
            icon={<Disc aria-hidden="true" />}
            label="Record"
            aria-label="Record locally to this device"
            loading={rec.state === 'starting' || rec.state === 'saving'}
            onClick={() => setInfoOpen(true)}
          />
        </Tip>
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
  const { meeting, isModerator, stageOnly, raisedHands, setHand, join, waitingCount } = useMeetingRoom();
  const { localParticipant, isMicrophoneEnabled, isCameraEnabled, isScreenShareEnabled } = useLocalParticipant();
  const [leaveOpen, setLeaveOpen] = useState(false);
  const [reactOpen, setReactOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const audioOnly = meeting.mediaMode === 'AUDIO_ONLY';
  const handRaised = !!raisedHands[join.participantId];
  // What the media server lets this participant publish (the host's permissions): turning something off always
  // works, turning on a restricted one is not offered (and would be refused by the server anyway).
  // A webinar attendee publishes in its own audience room; while that is not connected the controls run on the stage
  // room, where an attendee never publishes, so that grant says nothing about the host's permissions.
  const currentRoom = useRoomContext();
  const stageRoom = useContext(StageRoomContext);
  // These hooks only re-render on changes: their values lag a render behind when the room itself is swapped (the
  // audience room connecting or dropping), so the grant and state are read from the room in use right now. A room
  // that is connecting or dropping reports no rights, which is not the host's doing either.
  useLocalParticipantPermissions();
  useConnectionState(currentRoom);
  const grant = currentRoom.state === ConnectionState.Connected ? currentRoom.localParticipant.permissions : undefined;
  // The join says whether this participant has an audience room before the room state confirms the meeting's mode.
  const attendee = (stageOnly || !!join.audienceRoomName) && !isModerator;
  const { rights, known } = useCurrentRights(attendee && currentRoom === stageRoom ? undefined : grant);
  useMediaRightNotices(rights, known);
  const micBlocked = !rights.microphone;
  const camBlocked = !rights.camera;
  const shareBlocked = !rights.screenShare;

  const toggle = async (key: string, fn: () => Promise<unknown>, what: string) => {
    setBusy(key);
    try {
      await fn();
    } catch (e) {
      if (isPublishPermissionError(e)) {
        toast.warning(`${what === 'screen sharing' ? 'The host has restricted screen sharing' : `The host has restricted your ${what}`}.`, {
          id: `media-right-${key}`,
        });
        return;
      }
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

  const micLabel = isMicrophoneEnabled ? 'Mute microphone' : micBlocked ? restrictedLabel('microphone') : 'Unmute microphone';
  const camLabel = isCameraEnabled ? 'Turn camera off' : camBlocked ? restrictedLabel('camera') : 'Turn camera on';
  const shareLabel = isScreenShareEnabled ? 'Stop sharing screen' : shareBlocked ? restrictedLabel('screenShare') : 'Share screen';
  const toggleShare = () =>
    void toggle('share', () => localParticipant.setScreenShareEnabled(!isScreenShareEnabled, { audio: rights.microphone, selfBrowserSurface: 'exclude' }), 'screen sharing');
  const panelTone = (p: Exclude<SidePanel, null>): Tone => (panel === p ? 'active' : 'neutral');
  // Set while the phone menu opens a panel, so the menu does not pull the focus back when it closes.
  const openedPanel = useRef(false);
  const openPanel = (p: Exclude<SidePanel, null>) => {
    openedPanel.current = panel !== p;
    togglePanel(p);
  };

  const micRestricted = !isMicrophoneEnabled && micBlocked;
  const camRestricted = !isCameraEnabled && camBlocked;
  const shareRestricted = !isScreenShareEnabled && shareBlocked;
  const moreLabel = [
    'More options',
    unreadChat > 0 ? `${unreadChat} unread messages` : null,
    isModerator && waitingCount > 0 ? `${waitingCount} waiting` : null,
  ]
    .filter(Boolean)
    .join(', ');

  return (
    <TooltipProvider delayDuration={300}>
      {/* Below 1536px a flex row (centre group, then panels and Leave); from 1536px three equal zones, so the centre
          group is centred on the screen. */}
      <footer
        className="flex min-w-0 items-center gap-2 border-t border-white/10 bg-neutral-950 px-3 py-2.5 md:gap-4 md:px-4 xl:gap-6 xl:py-3 2xl:grid 2xl:grid-cols-[1fr_auto_1fr]"
        aria-label="Meeting controls"
      >
        {/* Left: time and meeting code (very wide screens; the title is in the header). */}
        <div className="hidden min-w-0 items-center gap-3 2xl:flex">
          <MeetingClock />
          {meeting.joinCode && (
            <>
              <span className="h-4 w-px shrink-0 bg-white/15" aria-hidden="true" />
              <span className="truncate font-mono text-sm text-neutral-400" title="Meeting code">
                {meeting.joinCode}
              </span>
            </>
          )}
        </div>

        {/* Centre: your media and actions. */}
        <div className="flex min-w-0 flex-1 items-center justify-center gap-1.5 md:gap-2 2xl:flex-none" role="group" aria-label="Your microphone, camera and actions">
          <div className="flex items-stretch">
            <Tip content={micLabel}>
              <CtrlButton
                joined
                tone={isMicrophoneEnabled ? 'neutral' : 'off'}
                icon={isMicrophoneEnabled ? <Mic aria-hidden="true" /> : <MicOff aria-hidden="true" />}
                label={isMicrophoneEnabled ? 'Mute' : micRestricted ? 'Restricted' : 'Unmute'}
                locked={micBlocked}
                blocked={micRestricted}
                aria-pressed={!isMicrophoneEnabled}
                aria-label={micLabel}
                loading={busy === 'mic'}
                onClick={() => void toggle('mic', () => localParticipant.setMicrophoneEnabled(!isMicrophoneEnabled), 'microphone')}
              />
            </Tip>
            <DeviceMenu kind="audioinput" label="Microphone" tone={isMicrophoneEnabled ? 'neutral' : 'off'} />
          </div>
          {!audioOnly && (
            <div className="flex items-stretch">
              <Tip content={camLabel}>
                <CtrlButton
                  joined
                  tone={isCameraEnabled ? 'neutral' : 'off'}
                  icon={isCameraEnabled ? <Video aria-hidden="true" /> : <VideoOff aria-hidden="true" />}
                  label={camRestricted ? 'Restricted' : 'Camera'}
                  locked={camBlocked}
                  blocked={camRestricted}
                  aria-pressed={!isCameraEnabled}
                  aria-label={camLabel}
                  loading={busy === 'cam'}
                  onClick={() => void toggle('cam', () => localParticipant.setCameraEnabled(!isCameraEnabled), 'camera')}
                />
              </Tip>
              <DeviceMenu kind="videoinput" label="Camera" tone={isCameraEnabled ? 'neutral' : 'off'} />
            </div>
          )}
          {!audioOnly && (
            <div className="hidden md:flex">
              <Tip content={shareLabel}>
                <CtrlButton
                  tone={isScreenShareEnabled ? 'on' : 'neutral'}
                  icon={isScreenShareEnabled ? <MonitorX aria-hidden="true" /> : <MonitorUp aria-hidden="true" />}
                  label={isScreenShareEnabled ? 'Stop sharing' : shareRestricted ? 'Restricted' : 'Share'}
                  locked={shareRestricted}
                  blocked={shareRestricted}
                  aria-pressed={isScreenShareEnabled}
                  aria-label={shareLabel}
                  disabled={busy === 'share'}
                  onClick={toggleShare}
                />
              </Tip>
            </div>
          )}
          <div className="hidden md:flex">
            <Tip content={handRaised ? 'Lower your hand' : 'Raise your hand'}>
              <CtrlButton
                tone={handRaised ? 'warn' : 'neutral'}
                icon={<Hand aria-hidden="true" />}
                label={handRaised ? 'Lower' : 'Raise'}
                aria-pressed={handRaised}
                aria-label={handRaised ? 'Lower hand' : 'Raise hand'}
                onClick={() => void raiseHand()}
              />
            </Tip>
          </div>
          <div className="hidden md:flex">
            <Popover open={reactOpen} onOpenChange={setReactOpen}>
              <Tip content="Send a reaction">
                <PopoverTrigger asChild>
                  <CtrlButton icon={<SmilePlus aria-hidden="true" />} label="React" aria-label="React with an emoji" tone={reactOpen ? 'active' : 'neutral'} />
                </PopoverTrigger>
              </Tip>
              <PopoverContent side="top" className="flex gap-1 border-white/10 bg-neutral-900 p-2">
                {REACTION_EMOJI.map((e) => (
                  <button
                    key={e}
                    className="rounded-lg p-1.5 text-2xl transition-transform hover:scale-110 hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                    aria-label={`React ${e}`}
                    onClick={() => void react(e)}
                  >
                    {e}
                  </button>
                ))}
              </PopoverContent>
            </Popover>
          </div>
          <LocalRecordButton onChange={onLocalRecordingChange} />

          {/* Small screens: everything else in one menu. */}
          <div className="flex md:hidden">
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <CtrlButton
                  icon={<MoreHorizontal aria-hidden="true" />}
                  label="More"
                  aria-label={moreLabel}
                  badge={
                    <>
                      <CountBadge count={unreadChat} />
                      {isModerator && <CountBadge count={waitingCount} tone="warning" side="left" />}
                    </>
                  }
                />
              </DropdownMenuTrigger>
              <DropdownMenuContent
                side="top"
                align="end"
                className="w-64"
                // A panel opened from here takes the focus; otherwise it returns to the More button.
                onCloseAutoFocus={(e) => {
                  if (openedPanel.current) {
                    openedPanel.current = false;
                    e.preventDefault();
                  }
                }}
              >
                {!audioOnly && (
                  <DropdownMenuItem disabled={shareRestricted || busy === 'share'} onSelect={toggleShare}>
                    {isScreenShareEnabled ? <MonitorX aria-hidden="true" /> : <MonitorUp aria-hidden="true" />} {shareLabel}
                  </DropdownMenuItem>
                )}
                <DropdownMenuItem onSelect={() => void raiseHand()}>
                  <Hand aria-hidden="true" /> {handRaised ? 'Lower hand' : 'Raise hand'}
                </DropdownMenuItem>
                <DropdownMenuSeparator />
                <DropdownMenuLabel>React</DropdownMenuLabel>
                <div className="grid grid-cols-4 gap-1 px-1 pb-1">
                  {REACTION_EMOJI.map((e) => (
                    <DropdownMenuItem key={e} className="justify-center px-0 text-xl" aria-label={`React ${e}`} onSelect={() => void react(e)}>
                      {e}
                    </DropdownMenuItem>
                  ))}
                </div>
                <DropdownMenuSeparator />
                <DropdownMenuItem onSelect={() => openPanel('chat')}>
                  <MessageSquare aria-hidden="true" /> Chat
                  {unreadChat > 0 && <span className="ml-auto rounded-full bg-red-600 px-1.5 text-[10px] font-bold text-white">{unreadChat}</span>}
                </DropdownMenuItem>
                <DropdownMenuItem onSelect={() => openPanel('participants')}>
                  <Users aria-hidden="true" /> Participants
                </DropdownMenuItem>
                {isModerator && (
                  <DropdownMenuItem onSelect={() => openPanel('moderation')}>
                    <Shield aria-hidden="true" /> Host controls
                    {waitingCount > 0 && <span className="ml-auto rounded-full bg-warning px-1.5 text-[10px] font-bold text-black">{waitingCount}</span>}
                  </DropdownMenuItem>
                )}
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        {/* Right: panels and Leave. */}
        <div className="flex shrink-0 items-center justify-end gap-1.5 md:gap-2">
          <div className="hidden items-center gap-2 md:flex" role="group" aria-label="Panels">
            <Tip content="Chat with everyone">
              <CtrlButton
                tone={panelTone('chat')}
                icon={<MessageSquare aria-hidden="true" />}
                label="Chat"
                aria-pressed={panel === 'chat'}
                aria-label={unreadChat ? `Chat, ${unreadChat} unread` : 'Chat'}
                badge={<CountBadge count={unreadChat} />}
                onClick={() => togglePanel('chat')}
              />
            </Tip>
            <Tip content="See who is here">
              <CtrlButton
                tone={panelTone('participants')}
                icon={<Users aria-hidden="true" />}
                label="Participants"
                aria-pressed={panel === 'participants'}
                aria-label="Participants"
                onClick={() => togglePanel('participants')}
              />
            </Tip>
            {isModerator && (
              <Tip content="Waiting room, permissions and recording">
                <CtrlButton
                  tone={panelTone('moderation')}
                  icon={<Shield aria-hidden="true" />}
                  label="Host"
                  aria-pressed={panel === 'moderation'}
                  aria-label={waitingCount ? `Host controls, ${waitingCount} waiting` : 'Host controls'}
                  badge={<CountBadge count={waitingCount} tone="warning" />}
                  onClick={() => togglePanel('moderation')}
                />
              </Tip>
            )}
            <span className="mx-1 h-8 w-px bg-white/10" aria-hidden="true" />
          </div>
          <Tip content="Leave meeting">
            <Button
              className={cn(CTRL_H, 'shrink-0 gap-2 rounded-xl bg-red-600 px-4 text-sm font-semibold text-white shadow-sm hover:bg-red-500 focus-visible:ring-offset-neutral-950 xl:px-5 [&_svg]:size-5')}
              onClick={() => setLeaveOpen(true)}
              aria-label="Leave meeting"
            >
              <PhoneOff aria-hidden="true" /> <span className="hidden xl:inline">Leave</span>
            </Button>
          </Tip>
        </div>
        <LeaveDialog open={leaveOpen} onOpenChange={setLeaveOpen} />
      </footer>
    </TooltipProvider>
  );
}
