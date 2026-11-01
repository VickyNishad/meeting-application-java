import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { meetingsApi, orgsApi } from '@/lib/api/endpoints';
import type { Meeting } from '@/lib/api/types';
import MeetingFormPage from './MeetingFormPage';

// Radix Switch measures itself; jsdom has no ResizeObserver.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
};

const base: Meeting = {
  id: 'm1',
  joinCode: 'abc-defg-hij',
  joinUrl: 'http://localhost/j/abc-defg-hij',
  title: 'Weekly sync',
  type: 'SCHEDULED',
  status: 'LIVE',
  mediaMode: 'VIDEO',
  privacy: 'LINK',
  host: { id: 'u1', displayName: 'Ada' },
  // Started two hours ago: re-sending this start time would be rejected (startTime < now - 5 min).
  startTime: new Date(Date.now() - 2 * 3_600_000).toISOString(),
  endTime: new Date(Date.now() - 1 * 3_600_000).toISOString(),
  timeZone: 'UTC',
  recurrenceRule: 'FREQ=WEEKLY',
  passwordProtected: false,
  waitingRoomEnabled: false,
  allowGuests: true,
  joinBeforeHost: false,
  muteOnEntry: false,
  maxParticipants: 100,
  screenSharePolicy: 'EVERYONE',
  locked: false,
  chatEnabled: true,
  createdAt: '2026-01-01T00:00:00Z',
  version: 3,
  autoRecord: false,
  recordingInProgress: false,
};

function renderEdit(meeting: Meeting) {
  vi.spyOn(meetingsApi, 'get').mockResolvedValue(meeting);
  vi.spyOn(orgsApi, 'list').mockResolvedValue([]);
  const update = vi.spyOn(meetingsApi, 'update').mockResolvedValue({ ...meeting, version: meeting.version + 1 });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={[`/meetings/${meeting.id}/edit`]}>
        <Routes>
          <Route path="/meetings/:meetingId/edit" element={<MeetingFormPage />} />
          <Route path="/meetings/:meetingId" element={<p>details page</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { update };
}

async function rename(to: string) {
  const title = await screen.findByLabelText('Title');
  await userEvent.clear(title);
  await userEvent.type(title, to);
  await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
  await screen.findByText('details page');
}

describe('MeetingFormPage edit (W-09)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('renaming a LIVE recurring meeting PATCHes only the title (no past startTime)', async () => {
    const { update } = renderEdit(base);
    await rename('Weekly sync (moved)');
    expect(update).toHaveBeenCalledWith('m1', { title: 'Weekly sync (moved)', version: 3 });
  });

  it('a changed setting is sent, unchanged schedule fields are not', async () => {
    const { update } = renderEdit(base);
    await userEvent.click(await screen.findByRole('switch', { name: 'Waiting room' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText('details page');
    expect(update).toHaveBeenCalledWith('m1', { waitingRoomEnabled: true, version: 3 });
  });

  it('turning on automatic recording sends autoRecord', async () => {
    const { update } = renderEdit(base);
    await userEvent.click(await screen.findByRole('switch', { name: 'Record automatically' }));
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await screen.findByText('details page');
    expect(update).toHaveBeenCalledWith('m1', { autoRecord: true, version: 3 });
  });

  it('shows the custom messages for an empty title and duration (zod 4 numberField)', async () => {
    const { update } = renderEdit(base);
    await userEvent.clear(await screen.findByLabelText('Title'));
    await userEvent.clear(screen.getByLabelText('Duration (minutes)'));
    await userEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('Title is required')).toBeInTheDocument();
    expect(screen.getByText('Enter a duration')).toBeInTheDocument();
    expect(update).not.toHaveBeenCalled();
  });

  it('a personal room keeps its type, needs no date and never sends schedule fields', async () => {
    const personal: Meeting = { ...base, type: 'PERSONAL_ROOM', status: 'SCHEDULED', startTime: undefined, endTime: undefined, recurrenceRule: undefined };
    const { update } = renderEdit(personal);
    expect(await screen.findByLabelText('Meeting type')).toHaveValue('PERSONAL_ROOM');
    expect(screen.queryByLabelText('Date')).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Duration (minutes)')).not.toBeInTheDocument();
    await rename('My room');
    expect(update).toHaveBeenCalledWith('m1', { title: 'My room', version: 3 });
  });
});
