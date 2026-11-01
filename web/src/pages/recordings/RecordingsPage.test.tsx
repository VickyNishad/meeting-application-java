import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { recordingsApi, speechRecordingsApi } from '@/lib/api/endpoints';
import type { Recording, SpeechRecording } from '@/lib/api/types';
import RecordingsPage from './RecordingsPage';

const failed = (id: string): Recording => ({
  id,
  meetingId: 'm1',
  meetingTitle: `Meeting ${id}`,
  kind: 'CLOUD',
  status: 'FAILED',
  startedAt: '2026-10-31T13:00:00Z',
  failureReason: 'Could not start recording: The media server is unavailable.',
  autoStarted: true,
});

const clip: SpeechRecording = {
  id: 'c1',
  meetingId: 'm1',
  meetingTitle: 'India',
  participantId: 'p1',
  participantName: 'Vicky Nishad',
  fileName: '311026190030_abcdef.webm',
  fileFormat: 'audio/webm',
  fileSize: 120_000,
  startTime: '2026-10-31T13:30:30Z',
  durationMs: 4_000,
  status: 'COMPLETED',
  createdAt: '2026-10-31T13:30:30Z',
};

function renderPage(recordings: Recording[]) {
  vi.spyOn(recordingsApi, 'library').mockResolvedValue({
    items: recordings,
    page: 0,
    size: 20,
    totalItems: recordings.length,
    totalPages: 1,
  });
  vi.spyOn(speechRecordingsApi, 'list').mockResolvedValue({ items: [clip], page: 0, size: 20, totalItems: 1, totalPages: 1 });
  vi.spyOn(speechRecordingsApi, 'facets').mockResolvedValue({ meetings: [], participants: [], dates: [] });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <RecordingsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

afterEach(() => vi.restoreAllMocks());

describe('RecordingsPage', () => {
  it('shows the speaking clips first and folds away whole-meeting recordings that all failed, deleting nothing', async () => {
    renderPage([failed('r1'), failed('r2')]);
    const clips = await screen.findByRole('heading', { name: 'Speaking clips' });
    const meetings = screen.getByRole('heading', { name: 'Whole-meeting recordings' });
    expect(clips.compareDocumentPosition(meetings) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(await screen.findByText(/Vicky Nishad/)).toBeInTheDocument();

    expect(await screen.findByText(/2 recordings, none of the latest could be saved/)).toBeInTheDocument();
    expect(screen.queryByText('Meeting r1')).not.toBeInTheDocument();

    const toggle = screen.getByRole('button', { name: 'Show 2' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.click(toggle);
    expect(screen.getByRole('button', { name: 'Hide' })).toHaveAttribute('aria-expanded', 'true');
    const list = document.getElementById('meeting-recordings-list')!;
    expect(within(list).getByText('Meeting r1')).toBeInTheDocument();
    expect(within(list).getByText('Meeting r2')).toBeInTheDocument();
  });

  it('keeps whole-meeting recordings open when any of them worked', async () => {
    renderPage([{ ...failed('ok'), status: 'AVAILABLE', failureReason: undefined }, failed('r2')]);
    expect(await screen.findByText('Meeting ok')).toBeInTheDocument();
    expect(screen.getByText('Meeting r2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide' })).toBeInTheDocument();
  });
});
