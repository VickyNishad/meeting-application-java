import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { meetingsApi } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/problem';
import { WaitingRoom } from './JoinFlow';

function renderWaiting(onCancel = vi.fn()) {
  const qc = new QueryClient();
  render(
    <QueryClientProvider client={qc}>
      <WaitingRoom meetingId="m1" participantId="p1" waitToken="w1" title="Weekly sync" onAdmitted={vi.fn()} onCancel={onCancel} />
    </QueryClientProvider>,
  );
  return { onCancel };
}

describe('WaitingRoom terminal errors (W-05)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('stops polling when the meeting was cancelled and offers a way out', async () => {
    const joinStatus = vi
      .spyOn(meetingsApi, 'joinStatus')
      .mockRejectedValue(new ApiError({ status: 409, code: 'MEETING_CANCELLED', title: 'Conflict', detail: 'The meeting was cancelled.' }));
    const { onCancel } = renderWaiting();
    expect(await screen.findByRole('heading', { name: /this meeting was cancelled/i })).toBeInTheDocument();
    expect(screen.queryByText(/retrying/i)).not.toBeInTheDocument();
    // No retries and no further polling (the interval is 2 s).
    await new Promise((r) => setTimeout(r, 2300));
    expect(joinStatus).toHaveBeenCalledTimes(1);
    await userEvent.click(screen.getByRole('button', { name: 'Back' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('shows the server message for a participant that no longer exists (404)', async () => {
    vi.spyOn(meetingsApi, 'joinStatus').mockRejectedValue(new ApiError({ status: 404, code: 'NOT_FOUND', title: 'Not Found', detail: 'Participant not found.' }));
    renderWaiting();
    expect(await screen.findByRole('heading', { name: /can't join right now/i })).toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent('Participant not found.');
  });
});
