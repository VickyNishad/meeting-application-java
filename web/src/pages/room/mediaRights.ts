import { useEffect, useRef, useState } from 'react';
import { toast } from 'sonner';
import type { MediaKindKey } from '@/lib/api/types';

/** What this participant may publish now. */
export type MediaRights = Record<MediaKindKey, boolean>;

/** The media server's TrackSource numbers (livekit protocol). */
const SOURCE = { camera: 1, microphone: 2, screenShare: 3 } as const;

/**
 * The participant's rights as granted by the media server, which enforces them (it refuses a source that is not
 * granted and unpublishes a track whose grant is withdrawn); the host's settings reach clients only through it.
 * Unknown permissions (not connected yet) are treated as allowed: nothing can be published before they arrive.
 */
export function mediaRights(p: { canPublish: boolean; canPublishSources: readonly number[] } | undefined): MediaRights {
  if (!p) return { microphone: true, camera: true, screenShare: true };
  if (!p.canPublish) return { microphone: false, camera: false, screenShare: false };
  // No list means every source is allowed.
  const has = (s: number) => p.canPublishSources.length === 0 || p.canPublishSources.includes(s);
  return { microphone: has(SOURCE.microphone), camera: has(SOURCE.camera), screenShare: has(SOURCE.screenShare) };
}

type Grant = { canPublish: boolean; canPublishSources: readonly number[] };

/**
 * The rights to show, from the grant of the room this participant publishes in. While that grant is unknown (a webinar
 * attendee whose audience room is not connected, or reconnecting) the last known rights stay, so a lost connection is
 * never mistaken for a change by the host. {@code known}: a grant has been seen at least once.
 */
export function useCurrentRights(grant: Grant | undefined): { rights: MediaRights; known: boolean } {
  const key = grant ? `${grant.canPublish}:${grant.canPublishSources.join(',')}` : null;
  const [last, setLast] = useState<{ key: string; rights: MediaRights } | null>(null);
  if (grant && key !== null && key !== last?.key) {
    // Derived state: adopted during render, so the controls never show a stale grant for a frame.
    setLast({ key, rights: mediaRights(grant) });
  }
  return { rights: last?.rights ?? mediaRights(undefined), known: last !== null };
}

const LABEL: Record<MediaKindKey, string> = { microphone: 'microphone', camera: 'camera', screenShare: 'screen sharing' };

export function restrictedLabel(kind: MediaKindKey): string {
  return kind === 'screenShare' ? 'The host has restricted screen sharing' : `The host has restricted your ${LABEL[kind]}`;
}

/** A publish refused for lack of permission (the client checks the grant before asking the media server). */
export function isPublishPermissionError(e: unknown): boolean {
  return (
    typeof e === 'object' &&
    e !== null &&
    (e as { name?: unknown }).name === 'PublishTrackError' &&
    (e as { status?: unknown }).status === 403
  );
}

/** Tells the participant when the host restricts or allows one of their media (not on the first grant). */
export function useMediaRightNotices(rights: MediaRights, known: boolean): void {
  const previous = useRef<MediaRights | null>(null);
  useEffect(() => {
    if (!known) return;
    const before = previous.current;
    previous.current = rights;
    if (!before) return;
    for (const kind of Object.keys(LABEL) as MediaKindKey[]) {
      if (before[kind] === rights[kind]) continue;
      if (rights[kind]) {
        toast.info(kind === 'screenShare' ? 'The host allowed you to share your screen.' : `The host allowed you to use your ${LABEL[kind]}.`, {
          id: `media-right-${kind}`,
        });
      } else {
        toast.warning(
          kind === 'microphone'
            ? 'The host has restricted your microphone. You are muted and cannot unmute until the host allows it.'
            : kind === 'camera'
              ? 'The host has restricted your camera. It is off and cannot be turned on until the host allows it.'
              : 'The host has restricted screen sharing. Any screen share of yours was stopped.',
          { id: `media-right-${kind}` },
        );
      }
    }
  }, [rights, known]);
}
