import { useCallback, useEffect, useRef, useState } from 'react';
import { createLocalAudioTrack, createLocalVideoTrack, Room, type LocalAudioTrack, type LocalVideoTrack } from 'livekit-client';
import { AlertTriangle, Mic, MicOff, Video, VideoOff } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Alert, Label, NativeSelect, Switch } from '@/components/ui/primitives';
import type { DevicePrefs } from '@/lib/joinStore';
import { cn } from '@/lib/utils';

function mediaErrorMessage(e: unknown, what: string): string {
  if (e instanceof DOMException || e instanceof Error) {
    if (e.name === 'NotAllowedError' || e.name === 'SecurityError') return `Permission to use the ${what} was denied. Allow access in your browser's site settings.`;
    if (e.name === 'NotFoundError' || e.name === 'OverconstrainedError') return `No ${what} was found.`;
    if (e.name === 'NotReadableError') return `The ${what} is in use by another application.`;
    return `Could not start the ${what}: ${e.message}`;
  }
  return `Could not start the ${what}.`;
}

/** Live input level 0..1 of a local audio track using a WebAudio analyser. */
function useMicLevel(track: LocalAudioTrack | null): number {
  const [level, setLevel] = useState(0);
  useEffect(() => {
    if (!track) {
      setLevel(0);
      return;
    }
    const Ctx = window.AudioContext ?? (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
    if (!Ctx) return;
    const ctx = new Ctx();
    const source = ctx.createMediaStreamSource(new MediaStream([track.mediaStreamTrack]));
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 512;
    source.connect(analyser);
    const data = new Uint8Array(analyser.fftSize);
    let raf = 0;
    let last = 0;
    const tick = (t: number) => {
      analyser.getByteTimeDomainData(data);
      let sum = 0;
      for (const v of data) {
        const x = (v - 128) / 128;
        sum += x * x;
      }
      const rms = Math.sqrt(sum / data.length);
      if (t - last > 60) {
        setLevel(Math.min(1, rms * 4));
        last = t;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => {
      cancelAnimationFrame(raf);
      source.disconnect();
      void ctx.close().catch(() => undefined);
    };
  }, [track]);
  return level;
}

export function MicMeter({ level, label = 'Microphone level' }: { level: number; label?: string }) {
  const bars = 12;
  const active = Math.round(level * bars);
  return (
    <div className="flex h-4 items-end gap-0.5" role="meter" aria-label={label} aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(level * 100)}>
      {Array.from({ length: bars }, (_, i) => (
        <span
          key={i}
          className={cn('w-1.5 rounded-sm transition-colors', i < active ? (i > 9 ? 'bg-destructive' : 'bg-success') : 'bg-muted-foreground/25')}
          style={{ height: `${30 + (i / bars) * 70}%` }}
        />
      ))}
    </div>
  );
}

export function PreJoin({
  audioOnlyMeeting,
  displayName,
  value,
  onChange,
}: {
  audioOnlyMeeting: boolean;
  displayName?: string;
  value: DevicePrefs;
  onChange: (prefs: DevicePrefs) => void;
}) {
  const videoEl = useRef<HTMLVideoElement>(null);
  const [videoTrack, setVideoTrack] = useState<LocalVideoTrack | null>(null);
  const [audioTrack, setAudioTrack] = useState<LocalAudioTrack | null>(null);
  const [videoError, setVideoError] = useState<string | null>(null);
  const [audioError, setAudioError] = useState<string | null>(null);
  const [cams, setCams] = useState<MediaDeviceInfo[]>([]);
  const [mics, setMics] = useState<MediaDeviceInfo[]>([]);
  const [speakers, setSpeakers] = useState<MediaDeviceInfo[]>([]);
  const level = useMicLevel(audioTrack);
  // Latest prefs/onChange for the async device callbacks below.
  const latest = useRef({ value, onChange });
  useEffect(() => {
    latest.current = { value, onChange };
  }, [value, onChange]);
  /** A device that cannot start (permission denied, missing, busy) is switched off, so it isn't published on join. */
  const disableAfterFailure = useCallback((patch: Partial<DevicePrefs>) => {
    const next = { ...latest.current.value, ...patch };
    // Both devices can fail in the same tick, before a re-render: chain the patches.
    latest.current = { ...latest.current, value: next };
    latest.current.onChange(next);
  }, []);

  const videoOn = value.videoEnabled && !audioOnlyMeeting;
  const sinkSupported = typeof HTMLMediaElement !== 'undefined' && 'setSinkId' in HTMLMediaElement.prototype;

  const refreshDevices = useCallback(async () => {
    try {
      const [v, a, o] = await Promise.all([
        Room.getLocalDevices('videoinput', false),
        Room.getLocalDevices('audioinput', false),
        Room.getLocalDevices('audiooutput', false),
      ]);
      setCams(v);
      setMics(a);
      setSpeakers(o);
    } catch {
      // enumerateDevices unsupported: selection lists stay empty
    }
  }, []);

  useEffect(() => {
    void refreshDevices();
    const md = navigator.mediaDevices;
    if (!md?.addEventListener) return;
    const handler = () => void refreshDevices();
    md.addEventListener('devicechange', handler);
    return () => md.removeEventListener('devicechange', handler);
  }, [refreshDevices]);

  // Camera preview
  useEffect(() => {
    if (!videoOn) {
      setVideoTrack(null);
      return;
    }
    let cancelled = false;
    let created: LocalVideoTrack | null = null;
    setVideoError(null);
    createLocalVideoTrack({ deviceId: value.videoDeviceId, resolution: { width: 1280, height: 720 } })
      .then((t) => {
        if (cancelled) {
          t.stop();
          return;
        }
        created = t;
        setVideoTrack(t);
        void refreshDevices();
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setVideoError(mediaErrorMessage(e, 'camera'));
        disableAfterFailure({ videoEnabled: false });
      });
    return () => {
      cancelled = true;
      created?.stop();
    };
  }, [videoOn, value.videoDeviceId, refreshDevices, disableAfterFailure]);

  useEffect(() => {
    const el = videoEl.current;
    if (!videoTrack || !el) return;
    videoTrack.attach(el);
    return () => {
      videoTrack.detach(el);
    };
  }, [videoTrack]);

  // Microphone test
  useEffect(() => {
    if (!value.audioEnabled) {
      setAudioTrack(null);
      return;
    }
    let cancelled = false;
    let created: LocalAudioTrack | null = null;
    setAudioError(null);
    createLocalAudioTrack({ deviceId: value.audioDeviceId, echoCancellation: true, noiseSuppression: true })
      .then((t) => {
        if (cancelled) {
          t.stop();
          return;
        }
        created = t;
        setAudioTrack(t);
        void refreshDevices();
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setAudioError(mediaErrorMessage(e, 'microphone'));
        disableAfterFailure({ audioEnabled: false });
      });
    return () => {
      cancelled = true;
      created?.stop();
    };
  }, [value.audioEnabled, value.audioDeviceId, refreshDevices, disableAfterFailure]);

  const set = (patch: Partial<DevicePrefs>) => onChange({ ...value, ...patch });

  return (
    <div className="grid gap-4">
      <div className="relative aspect-video overflow-hidden rounded-xl bg-neutral-900">
        {videoOn && videoTrack ? (
          <video ref={videoEl} className="size-full -scale-x-100 object-cover" muted playsInline autoPlay aria-label="Camera preview" />
        ) : (
          <div className="flex size-full flex-col items-center justify-center gap-2 text-neutral-300">
            <div className="flex size-16 items-center justify-center rounded-full bg-neutral-700 text-xl font-semibold">
              {(displayName ?? '?').slice(0, 1).toUpperCase()}
            </div>
            <span className="text-sm">{audioOnlyMeeting ? 'Voice-only meeting' : value.videoEnabled ? 'Starting camera…' : 'Camera is off'}</span>
          </div>
        )}
        <div className="absolute inset-x-0 bottom-0 flex items-center justify-center gap-3 bg-gradient-to-t from-black/70 to-transparent p-3">
          <Button
            type="button"
            size="icon-lg"
            variant={value.audioEnabled ? 'secondary' : 'destructive'}
            aria-pressed={value.audioEnabled}
            aria-label={value.audioEnabled ? 'Turn microphone off' : 'Turn microphone on'}
            onClick={() => set({ audioEnabled: !value.audioEnabled })}
          >
            {value.audioEnabled ? <Mic aria-hidden="true" /> : <MicOff aria-hidden="true" />}
          </Button>
          {!audioOnlyMeeting && (
            <Button
              type="button"
              size="icon-lg"
              variant={value.videoEnabled ? 'secondary' : 'destructive'}
              aria-pressed={value.videoEnabled}
              aria-label={value.videoEnabled ? 'Turn camera off' : 'Turn camera on'}
              onClick={() => set({ videoEnabled: !value.videoEnabled })}
            >
              {value.videoEnabled ? <Video aria-hidden="true" /> : <VideoOff aria-hidden="true" />}
            </Button>
          )}
        </div>
      </div>

      {(videoError || audioError) && (
        <Alert variant="warning">
          <AlertTriangle aria-hidden="true" />
          <div className="grid gap-1">
            {videoError && <span>{videoError}</span>}
            {audioError && <span>{audioError}</span>}
          </div>
        </Alert>
      )}

      <div className="grid gap-3 sm:grid-cols-2">
        <div className="grid gap-1.5">
          <Label htmlFor="pj-mic">Microphone</Label>
          <NativeSelect id="pj-mic" value={value.audioDeviceId ?? ''} onChange={(e) => set({ audioDeviceId: e.target.value || undefined })} disabled={mics.length === 0}>
            <option value="">System default</option>
            {mics.filter((d) => d.deviceId && d.deviceId !== 'default').map((d, i) => (
              <option key={d.deviceId} value={d.deviceId}>
                {d.label || `Microphone ${i + 1}`}
              </option>
            ))}
          </NativeSelect>
          <MicMeter level={level} />
        </div>
        {!audioOnlyMeeting && (
          <div className="grid content-start gap-1.5">
            <Label htmlFor="pj-cam">Camera</Label>
            <NativeSelect id="pj-cam" value={value.videoDeviceId ?? ''} onChange={(e) => set({ videoDeviceId: e.target.value || undefined })} disabled={cams.length === 0}>
              <option value="">System default</option>
              {cams.filter((d) => d.deviceId && d.deviceId !== 'default').map((d, i) => (
                <option key={d.deviceId} value={d.deviceId}>
                  {d.label || `Camera ${i + 1}`}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
        {sinkSupported && speakers.length > 0 && (
          <div className="grid content-start gap-1.5">
            <Label htmlFor="pj-spk">Speaker</Label>
            <NativeSelect id="pj-spk" value={value.audioOutputDeviceId ?? ''} onChange={(e) => set({ audioOutputDeviceId: e.target.value || undefined })}>
              <option value="">System default</option>
              {speakers.filter((d) => d.deviceId && d.deviceId !== 'default').map((d, i) => (
                <option key={d.deviceId} value={d.deviceId}>
                  {d.label || `Speaker ${i + 1}`}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
        {!audioOnlyMeeting && (
          <div className="flex items-center justify-between gap-3 rounded-lg border p-3 sm:col-span-2">
            <div>
              <Label htmlFor="pj-audio-only">Join with audio only</Label>
              <p className="text-xs text-muted-foreground">Saves bandwidth; you won't publish video.</p>
            </div>
            <Switch id="pj-audio-only" checked={!value.videoEnabled} onCheckedChange={(c) => set({ videoEnabled: !c })} />
          </div>
        )}
      </div>
    </div>
  );
}
