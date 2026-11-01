import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { useState } from 'react';
import type { DevicePrefs } from '@/lib/joinStore';
import { PreJoin } from './PreJoin';

vi.mock('livekit-client', () => ({
  createLocalVideoTrack: vi.fn(() => Promise.reject(new DOMException('Permission denied', 'NotAllowedError'))),
  createLocalAudioTrack: vi.fn(() => Promise.reject(new DOMException('Permission denied', 'NotAllowedError'))),
  Room: { getLocalDevices: vi.fn(async () => []) },
}));

globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

function Harness({ onPrefs }: { onPrefs: (p: DevicePrefs) => void }) {
  const [prefs, setPrefs] = useState<DevicePrefs>({ audioEnabled: true, videoEnabled: true, audioDeviceId: 'mic-1' });
  return (
    <PreJoin
      audioOnlyMeeting={false}
      value={prefs}
      onChange={(p) => {
        onPrefs(p);
        setPrefs(p);
      }}
    />
  );
}

describe('PreJoin permission denial (W-07)', () => {
  it('switches a denied camera and microphone off in the prefs, keeping the explanation visible', async () => {
    const onPrefs = vi.fn();
    render(<Harness onPrefs={onPrefs} />);
    await waitFor(() => expect(onPrefs).toHaveBeenLastCalledWith({ audioEnabled: false, videoEnabled: false, audioDeviceId: 'mic-1' }));
    expect(screen.getByText(/permission to use the camera was denied/i)).toBeInTheDocument();
    expect(screen.getByText(/permission to use the microphone was denied/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Turn microphone on' })).toHaveAttribute('aria-pressed', 'false');
    expect(screen.getByRole('button', { name: 'Turn camera on' })).toHaveAttribute('aria-pressed', 'false');
  });
});
