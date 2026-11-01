/**
 * Client-side ("local") meeting recording.
 *
 * Captures the meeting tab/screen with getDisplayMedia (preferCurrentTab where supported), mixes the
 * room's local + remote audio tracks (plus any captured tab/system audio) through WebAudio, and records
 * the result with MediaRecorder. The file is kept in memory and downloaded to the user's device on stop.
 */

export const CANDIDATE_MIME_TYPES = [
  'video/webm;codecs=vp9,opus',
  'video/webm;codecs=vp8,opus',
  'video/webm;codecs=h264,opus',
  'video/webm',
  'video/mp4;codecs=avc1,mp4a.40.2',
  'video/mp4',
] as const;

export interface RecorderEnvironment {
  MediaRecorder?: { isTypeSupported?: (type: string) => boolean };
  mediaDevices?: { getDisplayMedia?: unknown };
  AudioContext?: unknown;
  isSecureContext?: boolean;
}

export type SupportResult =
  | { supported: true; mimeType: string; extension: 'webm' | 'mp4'; audioMixing: boolean }
  | { supported: false; reason: string };

export function defaultEnvironment(): RecorderEnvironment {
  if (typeof window === 'undefined') return {};
  const w = window as unknown as {
    MediaRecorder?: RecorderEnvironment['MediaRecorder'];
    AudioContext?: unknown;
    webkitAudioContext?: unknown;
    isSecureContext?: boolean;
  };
  return {
    MediaRecorder: w.MediaRecorder,
    mediaDevices: typeof navigator !== 'undefined' ? (navigator.mediaDevices as RecorderEnvironment['mediaDevices']) : undefined,
    AudioContext: w.AudioContext ?? w.webkitAudioContext,
    isSecureContext: w.isSecureContext,
  };
}

/** Feature-detects everything local recording needs and picks the best supported container. */
export function detectLocalRecordingSupport(env: RecorderEnvironment = defaultEnvironment()): SupportResult {
  if (env.isSecureContext === false) {
    return { supported: false, reason: 'Local recording requires a secure (HTTPS) connection.' };
  }
  if (!env.MediaRecorder) {
    return { supported: false, reason: 'This browser does not support MediaRecorder.' };
  }
  if (!env.mediaDevices || typeof env.mediaDevices.getDisplayMedia !== 'function') {
    return { supported: false, reason: 'This browser cannot capture the screen or tab (getDisplayMedia is unavailable).' };
  }
  const isTypeSupported = env.MediaRecorder.isTypeSupported;
  let mimeType: string | undefined;
  if (typeof isTypeSupported === 'function') {
    mimeType = CANDIDATE_MIME_TYPES.find((t) => {
      try {
        return isTypeSupported.call(env.MediaRecorder, t);
      } catch {
        return false;
      }
    });
  }
  if (!mimeType) {
    return { supported: false, reason: 'This browser cannot record video in a supported format (WebM or MP4).' };
  }
  return {
    supported: true,
    mimeType,
    extension: mimeType.startsWith('video/mp4') ? 'mp4' : 'webm',
    audioMixing: typeof env.AudioContext === 'function',
  };
}

export type RecorderErrorCode =
  | 'NOT_SUPPORTED'
  | 'PERMISSION_DENIED'
  | 'CAPTURE_FAILED'
  | 'RECORDER_ERROR'
  | 'STORAGE_LIMIT'
  | 'EXPORT_FAILED'
  | 'EMPTY_RECORDING';

export class RecorderError extends Error {
  readonly code: RecorderErrorCode;
  constructor(code: RecorderErrorCode, message: string) {
    super(message);
    this.name = 'RecorderError';
    this.code = code;
  }
}

export function describeCaptureError(e: unknown): RecorderError {
  const name = e instanceof Error || e instanceof DOMException ? e.name : '';
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return new RecorderError('PERMISSION_DENIED', 'Screen capture permission was denied or the picker was dismissed.');
  }
  if (name === 'NotFoundError') return new RecorderError('CAPTURE_FAILED', 'No screen or tab was available to capture.');
  if (name === 'NotReadableError' || name === 'AbortError') {
    return new RecorderError('CAPTURE_FAILED', 'The screen could not be captured (it may be in use or protected).');
  }
  const msg = e instanceof Error ? e.message : String(e);
  return new RecorderError('CAPTURE_FAILED', `Screen capture failed: ${msg}`);
}

export type StopReason = 'user' | 'capture-ended' | 'size-limit' | 'error';

export interface LocalRecorderOptions {
  /** Audio tracks of the meeting (local mic + remote participants) to mix in at start. */
  audioTracks: () => MediaStreamTrack[];
  /** Max in-memory size before auto-stopping (default 2 GiB). */
  maxBytes?: number;
  /** Fired when recording stops for any reason other than an explicit stop() call. */
  onAutoStop?: (reason: Exclude<StopReason, 'user'>, error?: RecorderError) => void;
  env?: RecorderEnvironment;
}

const DEFAULT_MAX_BYTES = 2 * 1024 * 1024 * 1024;

export class LocalRecorder {
  private readonly opts: LocalRecorderOptions;
  private display: MediaStream | null = null;
  private audioCtx: AudioContext | null = null;
  private destination: MediaStreamAudioDestinationNode | null = null;
  private sources = new Map<string, MediaStreamAudioSourceNode>();
  private recorder: MediaRecorder | null = null;
  private chunks: Blob[] = [];
  private bytes = 0;
  private stopPromise: Promise<Blob> | null = null;
  private stopResolve: ((b: Blob) => void) | null = null;
  private stopReject: ((e: unknown) => void) | null = null;
  private stopReason: StopReason = 'user';
  private support: Extract<SupportResult, { supported: true }> | null = null;
  startedAt = 0;

  constructor(opts: LocalRecorderOptions) {
    this.opts = opts;
  }

  get active(): boolean {
    return this.recorder?.state === 'recording' || this.recorder?.state === 'paused';
  }

  get sizeBytes(): number {
    return this.bytes;
  }

  get extension(): string {
    return this.support?.extension ?? 'webm';
  }

  /** Step 1: ask the user what to capture. Throws RecorderError on denial. */
  async acquire(): Promise<void> {
    const support = detectLocalRecordingSupport(this.opts.env);
    if (!support.supported) throw new RecorderError('NOT_SUPPORTED', support.reason);
    this.support = support;
    const constraints = {
      video: { frameRate: { ideal: 30 }, displaySurface: 'browser' },
      audio: { suppressLocalAudioPlayback: false },
      preferCurrentTab: true,
      selfBrowserSurface: 'include',
      surfaceSwitching: 'exclude',
      systemAudio: 'include',
    } as DisplayMediaStreamOptions;
    try {
      this.display = await navigator.mediaDevices.getDisplayMedia(constraints);
    } catch (e) {
      throw describeCaptureError(e);
    }
  }

  /** Step 2: start recording the acquired capture. */
  start(): void {
    if (!this.display || !this.support) throw new RecorderError('CAPTURE_FAILED', 'Nothing to record — call acquire() first.');
    const videoTrack = this.display.getVideoTracks()[0];
    if (!videoTrack) throw new RecorderError('CAPTURE_FAILED', 'The capture has no video track.');

    const tracks: MediaStreamTrack[] = [videoTrack];
    if (this.support.audioMixing) {
      const Ctx = (window.AudioContext ??
        (window as unknown as { webkitAudioContext: typeof AudioContext }).webkitAudioContext) as typeof AudioContext;
      this.audioCtx = new Ctx();
      this.destination = this.audioCtx.createMediaStreamDestination();
      for (const t of this.display.getAudioTracks()) this.addAudioTrack(t);
      for (const t of this.opts.audioTracks()) this.addAudioTrack(t);
      const mixed = this.destination.stream.getAudioTracks()[0];
      if (mixed) tracks.push(mixed);
    } else {
      // No WebAudio: fall back to whatever audio the capture provides.
      tracks.push(...this.display.getAudioTracks());
    }

    const stream = new MediaStream(tracks);
    let recorder: MediaRecorder;
    try {
      recorder = new MediaRecorder(stream, { mimeType: this.support.mimeType, videoBitsPerSecond: 2_500_000, audioBitsPerSecond: 128_000 });
    } catch (e) {
      this.cleanup();
      throw new RecorderError('RECORDER_ERROR', `Could not start the recorder: ${e instanceof Error ? e.message : String(e)}`);
    }
    this.recorder = recorder;
    this.chunks = [];
    this.bytes = 0;
    this.stopReason = 'user';
    this.stopPromise = new Promise<Blob>((resolve, reject) => {
      this.stopResolve = resolve;
      this.stopReject = reject;
    });
    // Avoid unhandled rejections if nobody awaits (auto-stop paths call finalize themselves).
    this.stopPromise.catch(() => undefined);

    const maxBytes = this.opts.maxBytes ?? DEFAULT_MAX_BYTES;
    recorder.ondataavailable = (ev: BlobEvent) => {
      if (ev.data && ev.data.size > 0) {
        this.chunks.push(ev.data);
        this.bytes += ev.data.size;
        if (this.bytes >= maxBytes && recorder.state === 'recording') {
          this.stopReason = 'size-limit';
          recorder.stop();
        }
      }
    };
    recorder.onerror = (ev: Event) => {
      const err = (ev as unknown as { error?: Error }).error;
      this.stopReason = 'error';
      this.lastError = new RecorderError('RECORDER_ERROR', `Recording failed: ${err?.message ?? 'unknown error'}`);
      if (recorder.state !== 'inactive') recorder.stop();
    };
    recorder.onstop = () => {
      const type = this.support?.mimeType.split(';')[0] ?? 'video/webm';
      const chunks = this.chunks;
      this.chunks = [];
      this.cleanup();
      if (chunks.length === 0) {
        this.stopReject?.(new RecorderError('EMPTY_RECORDING', 'The recording is empty — nothing was captured.'));
      } else {
        try {
          this.stopResolve?.(new Blob(chunks, { type }));
        } catch {
          this.stopReject?.(new RecorderError('STORAGE_LIMIT', 'Not enough memory to assemble the recording.'));
        }
      }
      if (this.stopReason !== 'user') {
        const reason = this.stopReason;
        const error =
          reason === 'size-limit'
            ? new RecorderError('STORAGE_LIMIT', 'The recording reached the size limit and was stopped automatically.')
            : (this.lastError ?? undefined);
        this.opts.onAutoStop?.(reason, error);
      }
    };
    // User pressed the browser's "Stop sharing" button or closed the captured tab.
    videoTrack.addEventListener('ended', () => {
      if (recorder.state !== 'inactive') {
        this.stopReason = 'capture-ended';
        recorder.stop();
      }
    });

    recorder.start(1000);
    this.startedAt = Date.now();
  }

  private lastError: RecorderError | null = null;

  /** Adds another audio track (e.g. a participant who joined mid-recording). */
  addAudioTrack(track: MediaStreamTrack): void {
    if (!this.audioCtx || !this.destination || track.kind !== 'audio' || this.sources.has(track.id)) return;
    try {
      const src = this.audioCtx.createMediaStreamSource(new MediaStream([track]));
      src.connect(this.destination);
      this.sources.set(track.id, src);
    } catch {
      // Track may already be ended; ignore.
    }
  }

  removeAudioTrack(trackId: string): void {
    const src = this.sources.get(trackId);
    if (src) {
      src.disconnect();
      this.sources.delete(trackId);
    }
  }

  /** Resolves with the recorded file once MediaRecorder has flushed. */
  waitForResult(): Promise<Blob> {
    if (!this.stopPromise) return Promise.reject(new RecorderError('EMPTY_RECORDING', 'Recording was never started.'));
    return this.stopPromise;
  }

  /** Stops recording (user action) and returns the file. */
  stop(): Promise<Blob> {
    const p = this.waitForResult();
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.stopReason = 'user';
      this.recorder.stop();
    }
    return p;
  }

  /** Releases capture without recording (e.g. server refused the STARTED notification). */
  abort(): void {
    if (this.recorder && this.recorder.state !== 'inactive') {
      this.recorder.ondataavailable = null;
      this.recorder.onstop = null;
      this.recorder.stop();
    }
    this.cleanup();
  }

  private cleanup(): void {
    this.display?.getTracks().forEach((t) => t.stop());
    this.display = null;
    for (const s of this.sources.values()) s.disconnect();
    this.sources.clear();
    if (this.audioCtx) void this.audioCtx.close().catch(() => undefined);
    this.audioCtx = null;
    this.destination = null;
  }
}

/** Triggers a browser download of the recording. Throws RecorderError('EXPORT_FAILED'). */
export function downloadBlob(blob: Blob, fileName: string): void {
  let url: string | null = null;
  try {
    url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = fileName;
    a.rel = 'noopener';
    a.style.display = 'none';
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
  } catch (e) {
    throw new RecorderError('EXPORT_FAILED', `Could not save the recording: ${e instanceof Error ? e.message : String(e)}`);
  } finally {
    const u = url;
    if (u) setTimeout(() => URL.revokeObjectURL(u), 60_000);
  }
}

export function recordingFileName(title: string, ext: string, at: Date = new Date()): string {
  const safe = title.replace(/[^\p{L}\p{N}\-_ ]+/gu, '').trim().replace(/\s+/g, '-').slice(0, 60) || 'meeting';
  const stamp = at.toISOString().replace(/[:]/g, '-').replace(/\..+$/, '');
  return `${safe}-${stamp}.${ext}`;
}
