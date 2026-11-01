import { describe, expect, it, vi } from 'vitest';
import { ClipSession, clipFormat, type ClipApi, type RecorderLike } from './useSpeechRecorder';

class FakeRecorder implements RecorderLike {
  state = 'inactive';
  ondataavailable: ((e: { data: Blob }) => void) | null = null;
  onstop: (() => void) | null = null;
  timeslice = 0;
  start(timeslice: number) {
    this.state = 'recording';
    this.timeslice = timeslice;
  }
  stop() {
    if (this.state === 'inactive') return;
    this.state = 'inactive';
    this.emit('last');
    this.onstop?.();
  }
  emit(text: string) {
    this.ondataavailable?.({ data: Object.assign(new Blob([text]), { label: text }) });
  }
}

/** Lets pending work finish (jsdom's Blob.text() completes on a later task, not a microtask). */
const flush = async () => {
  for (let i = 0; i < 20; i++) await new Promise((r) => setTimeout(r, 0));
};
const noSleep = () => Promise.resolve();

function api(overrides: Partial<ClipApi> = {}) {
  const calls: string[] = [];
  // The session asks for the clip once the previous clip settled (a microtask later at the earliest): an answer
  // given before that is kept until it asks.
  let answered: { id: string } | null = null;
  let resolveStart: ((v: { id: string }) => void) | null = null;
  const impl: ClipApi = {
    start: vi.fn(
      () =>
        new Promise<{ id: string }>((r) => {
          if (answered) r(answered);
          else resolveStart = r;
        }),
    ),
    chunk: vi.fn(async (_id: string, seq: number, data: Blob) => {
      calls.push(`chunk ${seq} ${(data as Blob & { label: string }).label}`);
    }),
    finish: vi.fn(async (_id: string, chunks: number) => {
      calls.push(`finish ${chunks}`);
    }),
    ...overrides,
  };
  return {
    impl,
    calls,
    answerStart: (id = 'clip-1') => {
      answered = { id };
      resolveStart?.(answered);
    },
  };
}

describe('ClipSession', () => {
  it('records at once, keeps chunks until the server reserved the clip, then sends them in order and finishes', async () => {
    const a = api();
    const rec = new FakeRecorder();
    const session = new ClipSession(a.impl, rec, 'audio/webm', vi.fn(), noSleep);
    expect(rec.state).toBe('recording');
    expect(rec.timeslice).toBe(1000);
    rec.emit('a');
    rec.emit('b');
    await flush();
    expect(a.calls).toEqual([]); // no id yet
    a.answerStart();
    await flush();
    expect(a.calls).toEqual(['chunk 0 a', 'chunk 1 b']);
    rec.emit('c');
    session.stop();
    await flush();
    expect(a.calls).toEqual(['chunk 0 a', 'chunk 1 b', 'chunk 2 c', 'chunk 3 last', 'finish 4']);
    expect(session.done).toBe(true);
    expect(a.impl.start).toHaveBeenCalledWith('audio/webm');
  });

  it('retries a failed chunk and keeps the order', async () => {
    let failures = 1;
    const a = api();
    const chunk = a.impl.chunk as ReturnType<typeof vi.fn>;
    const real = chunk.getMockImplementation()!;
    chunk.mockImplementation(async (...args: unknown[]) => {
      if (failures-- > 0) throw new Error('network');
      return real(...args);
    });
    const rec = new FakeRecorder();
    const session = new ClipSession(a.impl, rec, 'audio/webm', vi.fn(), noSleep);
    a.answerStart();
    rec.emit('a');
    rec.emit('b');
    session.stop();
    await flush();
    expect(a.calls).toEqual(['chunk 0 a', 'chunk 1 b', 'chunk 2 last', 'finish 3']);
  });

  it('a clip the server refuses stops recording, uploads nothing and reports the error', async () => {
    const onError = vi.fn();
    const a = api({ start: vi.fn(() => Promise.reject(new Error('Speaking clips are only recorded ...'))) });
    const rec = new FakeRecorder();
    const session = new ClipSession(a.impl, rec, 'audio/webm', onError, noSleep);
    rec.emit('a');
    await flush();
    expect(rec.state).toBe('inactive');
    expect(a.calls).toEqual([]);
    expect(onError).toHaveBeenCalledTimes(1);
    expect(session.done).toBe(true);
  });

  it('gives up after repeated upload failures: stops recording and reports it (the server closes the clip)', async () => {
    const onError = vi.fn();
    const a = api({ chunk: vi.fn(() => Promise.reject(new Error('down'))) });
    const rec = new FakeRecorder();
    new ClipSession(a.impl, rec, 'audio/webm', onError, noSleep);
    a.answerStart();
    rec.emit('a');
    await flush();
    expect(a.impl.chunk).toHaveBeenCalledTimes(3);
    expect(rec.state).toBe('inactive');
    expect(onError).toHaveBeenCalledTimes(1);
    expect(a.impl.finish).not.toHaveBeenCalled();
  });

  it('a new turn opens its clip only after the previous clip is finished, and records meanwhile (QA RC-2)', async () => {
    // Opening a clip on the server closes any clip of the same participant still open, so the last chunk and the
    // finish of the previous turn must get there first.
    const log: string[] = [];
    let releaseFinish: () => void = () => undefined;
    const first = api({
      finish: vi.fn(() => new Promise<void>((r) => (releaseFinish = () => (log.push('finish first'), r())))),
    });
    const rec1 = new FakeRecorder();
    const one = new ClipSession(first.impl, rec1, 'audio/webm', vi.fn(), noSleep);
    first.answerStart('clip-1');
    one.stop(); // the turn ended; its finish is still on its way
    await flush();
    expect(first.impl.finish).toHaveBeenCalledTimes(1);

    const second = api({ start: vi.fn(async () => (log.push('start second'), { id: 'clip-2' })) });
    const rec2 = new FakeRecorder();
    new ClipSession(second.impl, rec2, 'audio/webm', vi.fn(), noSleep, one.settled);
    rec2.emit('a');
    await flush();
    expect(rec2.state).toBe('recording');
    expect(second.impl.start).not.toHaveBeenCalled();
    releaseFinish();
    await flush();
    expect(log).toEqual(['finish first', 'start second']);
    expect(second.calls).toEqual(['chunk 0 a']);
  });

  it('settles when the server refused the clip, so the next turn is not held up', async () => {
    const a = api({ start: vi.fn(() => Promise.reject(new Error('no'))) });
    const session = new ClipSession(a.impl, new FakeRecorder(), 'audio/webm', vi.fn(), noSleep);
    let settled = false;
    void session.settled.then(() => (settled = true));
    await flush();
    expect(settled).toBe(true);
  });

  it('stopping twice finishes once', async () => {
    const a = api();
    const rec = new FakeRecorder();
    const session = new ClipSession(a.impl, rec, 'audio/webm', vi.fn(), noSleep);
    a.answerStart();
    session.stop();
    session.stop();
    await flush();
    expect(a.impl.finish).toHaveBeenCalledTimes(1);
  });
});

describe('clipFormat', () => {
  it('picks WebM with video when the camera is on, audio-only otherwise, and nothing if WebM is unsupported', () => {
    const all = () => true;
    expect(clipFormat(true, all)).toBe('video/webm;codecs=vp8,opus');
    expect(clipFormat(false, all)).toBe('audio/webm;codecs=opus');
    expect(clipFormat(false, (t) => t === 'audio/webm')).toBe('audio/webm');
    expect(clipFormat(true, () => false)).toBeNull();
  });
});
