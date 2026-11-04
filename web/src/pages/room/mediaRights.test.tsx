import { afterEach, describe, expect, it, vi } from 'vitest';
import { renderHook } from '@testing-library/react';
import { toast } from 'sonner';
import { isPublishPermissionError, mediaRights, useCurrentRights, useMediaRightNotices, type MediaRights } from './mediaRights';

afterEach(() => vi.restoreAllMocks());

describe('mediaRights (from the media server grant)', () => {
  it('reads the granted sources; an empty list allows everything; no publishing allows nothing', () => {
    expect(mediaRights({ canPublish: true, canPublishSources: [1, 3, 4] })).toEqual({ microphone: false, camera: true, screenShare: true });
    expect(mediaRights({ canPublish: true, canPublishSources: [2] })).toEqual({ microphone: true, camera: false, screenShare: false });
    expect(mediaRights({ canPublish: true, canPublishSources: [] })).toEqual({ microphone: true, camera: true, screenShare: true });
    expect(mediaRights({ canPublish: false, canPublishSources: [] })).toEqual({ microphone: false, camera: false, screenShare: false });
    expect(mediaRights(undefined)).toEqual({ microphone: true, camera: true, screenShare: true });
  });

  it('recognises a publish refused for lack of permission', () => {
    expect(isPublishPermissionError(Object.assign(new Error('insufficient permissions'), { name: 'PublishTrackError', status: 403 }))).toBe(true);
    expect(isPublishPermissionError(Object.assign(new Error('timeout'), { name: 'PublishTrackError', status: 408 }))).toBe(false);
    expect(isPublishPermissionError(new DOMException('denied', 'NotAllowedError'))).toBe(false);
  });
});

describe('useMediaRightNotices', () => {
  const all: MediaRights = { microphone: true, camera: true, screenShare: true };

  it('tells the participant when the host restricts or allows a medium, not on the first grant', () => {
    const warning = vi.spyOn(toast, 'warning');
    const info = vi.spyOn(toast, 'info');
    const { rerender } = renderHook(({ r }: { r: MediaRights }) => useMediaRightNotices(r, true), { initialProps: { r: { ...all, camera: false } } });
    expect(warning).not.toHaveBeenCalled();
    rerender({ r: { ...all, camera: false, microphone: false } });
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('restricted your microphone'), expect.anything());
    rerender({ r: { ...all, camera: true, microphone: false } });
    expect(info).toHaveBeenCalledWith('The host allowed you to use your camera.', expect.anything());
    rerender({ r: { ...all, camera: true, microphone: false, screenShare: false } });
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('restricted screen sharing'), expect.anything());
    expect(warning).toHaveBeenCalledTimes(2);
  });

  it('says nothing until the grant is known', () => {
    const warning = vi.spyOn(toast, 'warning');
    const { rerender } = renderHook(({ r, k }: { r: MediaRights; k: boolean }) => useMediaRightNotices(r, k), { initialProps: { r: all, k: false } });
    rerender({ r: { ...all, microphone: false }, k: false });
    rerender({ r: { ...all, microphone: false }, k: true });
    expect(warning).not.toHaveBeenCalled();
  });
});

describe('useCurrentRights (webinar attendee whose own room is not connected)', () => {
  const grant = (sources: number[]) => ({ canPublish: true, canPublishSources: sources });

  it('keeps the last known rights while the grant is unknown, so a lost connection is no host change', () => {
    const warning = vi.spyOn(toast, 'warning');
    const info = vi.spyOn(toast, 'info');
    const { result, rerender } = renderHook(
      ({ g }: { g: ReturnType<typeof grant> | undefined }) => {
        const r = useCurrentRights(g);
        useMediaRightNotices(r.rights, r.known);
        return r;
      },
      { initialProps: { g: undefined as ReturnType<typeof grant> | undefined } },
    );
    // Not connected yet: nothing known, nothing announced when the first grant arrives.
    expect(result.current.known).toBe(false);
    rerender({ g: grant([1, 2, 3, 4]) });
    expect(result.current.rights).toEqual({ microphone: true, camera: true, screenShare: true });
    // The connection drops: the rights shown stay, no notice.
    rerender({ g: undefined });
    expect(result.current.rights).toEqual({ microphone: true, camera: true, screenShare: true });
    rerender({ g: grant([1, 2, 3, 4]) });
    expect(warning).not.toHaveBeenCalled();
    expect(info).not.toHaveBeenCalled();
    // A real change by the host is still announced.
    rerender({ g: grant([1, 3]) });
    expect(result.current.rights.microphone).toBe(false);
    expect(warning).toHaveBeenCalledWith(expect.stringContaining('restricted your microphone'), expect.anything());
  });
});
