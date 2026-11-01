import { describe, expect, it } from 'vitest';
import { CANDIDATE_MIME_TYPES, describeCaptureError, detectLocalRecordingSupport, recordingFileName } from './localRecorder';

const getDisplayMedia = () => Promise.resolve();
function AudioContextStub() {}

function env(supported: string[] | null, extra: Partial<Parameters<typeof detectLocalRecordingSupport>[0]> = {}) {
  return {
    isSecureContext: true,
    MediaRecorder: supported === null ? undefined : { isTypeSupported: (t: string) => supported.includes(t) },
    mediaDevices: { getDisplayMedia },
    AudioContext: AudioContextStub,
    ...extra,
  };
}

describe('detectLocalRecordingSupport', () => {
  it('prefers VP9/Opus WebM when available', () => {
    const r = detectLocalRecordingSupport(env([...CANDIDATE_MIME_TYPES]));
    expect(r).toEqual({ supported: true, mimeType: 'video/webm;codecs=vp9,opus', extension: 'webm', audioMixing: true });
  });

  it('falls back through the candidate list (VP8 → plain webm → mp4)', () => {
    expect(detectLocalRecordingSupport(env(['video/webm;codecs=vp8,opus', 'video/webm']))).toMatchObject({ mimeType: 'video/webm;codecs=vp8,opus' });
    expect(detectLocalRecordingSupport(env(['video/webm']))).toMatchObject({ mimeType: 'video/webm', extension: 'webm' });
    // Safari-like: only MP4
    expect(detectLocalRecordingSupport(env(['video/mp4']))).toMatchObject({ supported: true, mimeType: 'video/mp4', extension: 'mp4' });
  });

  it('reports missing MediaRecorder', () => {
    const r = detectLocalRecordingSupport(env(null));
    expect(r.supported).toBe(false);
    if (!r.supported) expect(r.reason).toMatch(/MediaRecorder/);
  });

  it('reports missing getDisplayMedia (e.g. most mobile browsers)', () => {
    const r = detectLocalRecordingSupport(env(['video/webm'], { mediaDevices: {} }));
    expect(r).toMatchObject({ supported: false });
    if (!r.supported) expect(r.reason).toMatch(/getDisplayMedia/);
  });

  it('reports when no container format is supported', () => {
    const r = detectLocalRecordingSupport(env(['audio/ogg']));
    expect(r).toMatchObject({ supported: false });
    if (!r.supported) expect(r.reason).toMatch(/format/);
  });

  it('requires a secure context', () => {
    const r = detectLocalRecordingSupport(env(['video/webm'], { isSecureContext: false }));
    expect(r).toMatchObject({ supported: false });
    if (!r.supported) expect(r.reason).toMatch(/HTTPS/);
  });

  it('handles MediaRecorder without isTypeSupported and throwing implementations', () => {
    expect(detectLocalRecordingSupport(env(['video/webm'], { MediaRecorder: {} })).supported).toBe(false);
    const throwing = { isTypeSupported: () => { throw new Error('boom'); } };
    expect(detectLocalRecordingSupport(env(['video/webm'], { MediaRecorder: throwing })).supported).toBe(false);
  });

  it('notes when WebAudio mixing is unavailable', () => {
    expect(detectLocalRecordingSupport(env(['video/webm'], { AudioContext: undefined }))).toMatchObject({ supported: true, audioMixing: false });
  });
});

describe('describeCaptureError', () => {
  it('maps permission denial', () => {
    expect(describeCaptureError(new DOMException('denied', 'NotAllowedError')).code).toBe('PERMISSION_DENIED');
  });
  it('maps other failures to CAPTURE_FAILED', () => {
    expect(describeCaptureError(new DOMException('busy', 'NotReadableError')).code).toBe('CAPTURE_FAILED');
    expect(describeCaptureError(new Error('weird')).code).toBe('CAPTURE_FAILED');
  });
});

describe('recordingFileName', () => {
  it('builds a safe, timestamped name', () => {
    expect(recordingFileName('Weekly sync: Q4/plan', 'webm', new Date('2026-10-27T09:30:15.123Z'))).toBe('Weekly-sync-Q4plan-2026-10-27T09-30-15.webm');
    expect(recordingFileName('***', 'mp4', new Date('2026-01-01T00:00:00Z'))).toBe('meeting-2026-01-01T00-00-00.mp4');
  });
});
