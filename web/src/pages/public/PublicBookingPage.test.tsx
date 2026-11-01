import { afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { bookingPagesApi, bookingsApi } from '@/lib/api/endpoints';
import { networkError } from '@/lib/api/problem';
import PublicBookingPage from './PublicBookingPage';

const start = new Date(Date.now() + 3 * 86_400_000);
start.setUTCMinutes(0, 0, 0);
const slot = { start: start.toISOString(), end: new Date(start.getTime() + 30 * 60_000).toISOString() };

function renderPage() {
  vi.spyOn(bookingPagesApi, 'publicPage').mockResolvedValue({
    slug: 'intro',
    title: 'Intro call',
    durationMinutes: 30,
    hostName: 'Ada',
    hostTimeZone: 'UTC',
    mediaMode: 'VIDEO',
  });
  vi.spyOn(bookingPagesApi, 'slots').mockResolvedValue({ timeZone: 'UTC', slots: [slot] });
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(
    <QueryClientProvider client={qc}>
      <MemoryRouter initialEntries={['/book/intro']}>
        <Routes>
          <Route path="/book/:slug" element={<PublicBookingPage />} />
          <Route path="/book/:slug/confirmed" element={<p>confirmed</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

const keyOf = (call: unknown[]) => call[1] as string;

describe('PublicBookingPage Idempotency-Key (W-12)', () => {
  afterEach(() => vi.restoreAllMocks());

  it('reuses the key for an identical retry and uses a new one once the request changes', async () => {
    const create = vi.spyOn(bookingsApi, 'create').mockRejectedValueOnce(networkError(new TypeError('x'))).mockRejectedValueOnce(networkError(new TypeError('x')));
    renderPage();
    const slotButtons = await screen.findAllByRole('button', { name: /\d{1,2}:\d{2}/ });
    await userEvent.click(slotButtons[0]!);
    await userEvent.type(screen.getByLabelText('Name'), 'Bo');
    await userEvent.type(screen.getByLabelText('Email'), 'bo@example.com');
    const confirm = screen.getByRole('button', { name: 'Confirm booking' });

    await userEvent.click(confirm); // response lost
    await userEvent.click(confirm); // identical retry
    await userEvent.type(screen.getByLabelText(/anything to share/i), 'Agenda attached');
    create.mockResolvedValueOnce({} as never);
    await userEvent.click(confirm); // changed request
    await screen.findByText('confirmed');

    expect(create).toHaveBeenCalledTimes(3);
    const [k1, k2, k3] = create.mock.calls.map(keyOf);
    expect(k2).toBe(k1);
    expect(k3).not.toBe(k1);
  });
});
