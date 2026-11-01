import { useEffect } from 'react';
import { RoomEvent, type Participant, type Room } from 'livekit-client';
import { speakerStatsApi } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/problem';

export const REPORT_INTERVAL_MS = 10_000;
/** The server accepts at most 30 s per participant and batch. */
const MAX_ENTRY_MS = 30_000;
const MAX_QUEUED_BATCHES = 6;
const NOT_REPORTER_BACKOFF_MS = 30_000;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export interface SpeakerEntry {
  participantId: string;
  speakingMs: number;
  turns: number;
}

/**
 * Speaking time from the SFU's active-speaker updates: who started speaking when, and for how long. `update` is
 * called with everyone speaking now; `drain` returns what accumulated since the last drain (ongoing speech included,
 * up to `now`) and starts over. Only our own participants count (identities are participant ids; the recorder and
 * other service participants are skipped).
 */
export class SpeakingTally {
  private readonly since = new Map<string, number>();
  private readonly totals = new Map<string, { ms: number; turns: number }>();

  update(activeIdentities: string[], now: number): void {
    const active = new Set(activeIdentities.filter((id) => UUID.test(id)));
    for (const [id, start] of this.since) {
      if (!active.has(id)) {
        this.add(id, now - start, 0);
        this.since.delete(id);
      }
    }
    for (const id of active) {
      if (!this.since.has(id)) {
        this.since.set(id, now);
        this.add(id, 0, 1);
      }
    }
  }

  drain(now: number): SpeakerEntry[] {
    for (const [id, start] of this.since) {
      this.add(id, now - start, 0);
      this.since.set(id, now);
    }
    const entries = [...this.totals.entries()]
      .map(([participantId, t]) => ({ participantId, speakingMs: Math.min(MAX_ENTRY_MS, Math.max(0, Math.round(t.ms))), turns: Math.min(100, t.turns) }))
      .filter((e) => e.speakingMs > 0 || e.turns > 0);
    this.totals.clear();
    return entries;
  }

  private add(id: string, ms: number, turns: number) {
    const t = this.totals.get(id) ?? { ms: 0, turns: 0 };
    t.ms += ms;
    t.turns += turns;
    this.totals.set(id, t);
  }
}

/**
 * Reports speaking time while this participant is a host or co-host. The server takes reports from one moderator
 * per session (the one present longest); others are told so and back off. A batch keeps its number until it is
 * accepted, so a retry is counted once. Numbers are millisecond timestamps: unique across page reloads.
 *
 * `rooms`: every media room the moderator is in (stage, and audience in HOST_AND_SELF_ONLY); who is speaking is the
 * union of their speakers. A room that is reconnecting or disconnected counts as silent.
 */
export function useSpeakerReporter(rooms: Room[], meetingId: string, participantId: string, enabled: boolean) {
  useEffect(() => {
    if (!enabled) return;
    const tally = new SpeakingTally();
    const queue: { batchSeq: number; entries: SpeakerEntry[] }[] = [];
    let pausedUntil = 0;
    let sending = false;
    let lastSeq = 0;

    const speaking = new Map<Room, string[]>();
    const recount = () => tally.update([...speaking.values()].flat(), Date.now());
    const cleanups = rooms.map((room) => {
      const onSpeakers = (speakers: Participant[]) => {
        speaking.set(room, speakers.map((p) => p.identity));
        recount();
      };
      const silent = () => {
        speaking.set(room, []);
        recount();
      };
      speaking.set(room, room.activeSpeakers.map((p) => p.identity));
      room.on(RoomEvent.ActiveSpeakersChanged, onSpeakers);
      room.on(RoomEvent.Reconnecting, silent);
      room.on(RoomEvent.Disconnected, silent);
      return () => {
        room.off(RoomEvent.ActiveSpeakersChanged, onSpeakers);
        room.off(RoomEvent.Reconnecting, silent);
        room.off(RoomEvent.Disconnected, silent);
      };
    });
    recount();

    const flush = async () => {
      const now = Date.now();
      const entries = tally.drain(now);
      if (now < pausedUntil) return; // another moderator reports: discard
      if (entries.length > 0) {
        lastSeq = Math.max(now, lastSeq + 1);
        queue.push({ batchSeq: lastSeq, entries });
        if (queue.length > MAX_QUEUED_BATCHES) queue.shift();
      }
      if (sending) return;
      sending = true;
      try {
        while (queue.length > 0) {
          const batch = queue[0];
          try {
            await speakerStatsApi.report(meetingId, { participantId, ...batch });
            queue.shift();
          } catch (e) {
            if (e instanceof ApiError && (e.code === 'SPEAKER_REPORTER_MISMATCH' || e.status === 403 || e.status === 409)) {
              queue.length = 0;
              pausedUntil = Date.now() + NOT_REPORTER_BACKOFF_MS;
            }
            return; // network/5xx: keep the batch for the next round
          }
        }
      } finally {
        sending = false;
      }
    };
    const timer = window.setInterval(() => void flush(), REPORT_INTERVAL_MS);
    return () => {
      cleanups.forEach((c) => c());
      window.clearInterval(timer);
      void flush();
    };
  }, [rooms, meetingId, participantId, enabled]);
}
