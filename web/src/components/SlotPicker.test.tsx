import { describe, expect, it, vi, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SlotList, SlotPicker } from './SlotPicker';
import { groupSlotsByDay } from '@/lib/time';
import { api } from '@/lib/api/client';

const slots = [
  { start: '2026-11-02T14:00:00Z', end: '2026-11-02T14:30:00Z' },
  { start: '2026-11-02T22:30:00Z', end: '2026-11-02T23:00:00Z' },
  { start: '2026-11-03T04:00:00Z', end: '2026-11-03T04:30:00Z' },
];

describe('SlotList', () => {
  it('renders one section per viewer-local day with times in that zone', () => {
    render(<SlotList days={groupSlotsByDay(slots, 'Asia/Kolkata', 'en-US')} onSelect={() => undefined} />);
    const sections = screen.getAllByRole('region');
    expect(sections).toHaveLength(2);
    expect(within(sections[0]!).getAllByRole('button').map((b) => b.textContent)).toEqual(['7:30 PM']);
    expect(within(sections[1]!).getAllByRole('button').map((b) => b.textContent)).toEqual(['4:00 AM', '9:30 AM']);
    expect(screen.getAllByTestId('slot-day').map((h) => h.textContent)).toEqual([
      // Default-locale formatting (e.g. "Monday, November 2" or "Monday, 2 November")
      expect.stringMatching(/Monday.*(November 2|2 November)/),
      expect.stringMatching(/Tuesday.*(November 3|3 November)/),
    ]);
  });

  it('renders the same slots on a single day for a New York viewer', () => {
    render(<SlotList days={groupSlotsByDay(slots, 'America/New_York', 'en-US')} onSelect={() => undefined} />);
    expect(screen.getAllByRole('region')).toHaveLength(1);
    expect(screen.getAllByRole('button').map((b) => b.textContent)).toEqual(['9:00 AM', '5:30 PM', '11:00 PM']);
  });

  it('calls onSelect with the UTC slot and marks the selection', async () => {
    const onSelect = vi.fn();
    const days = groupSlotsByDay(slots, 'UTC', 'en-US');
    const { rerender } = render(<SlotList days={days} onSelect={onSelect} />);
    await userEvent.click(screen.getByRole('button', { name: '10:30 PM' }));
    expect(onSelect).toHaveBeenCalledWith(expect.objectContaining({ start: '2026-11-02T22:30:00Z' }));
    rerender(<SlotList days={days} onSelect={onSelect} selected="2026-11-02T22:30:00Z" />);
    expect(screen.getByRole('button', { name: '10:30 PM' })).toHaveAttribute('aria-pressed', 'true');
  });

  it('shows an empty message when there are no slots', () => {
    render(<SlotList days={[]} onSelect={() => undefined} />);
    expect(screen.getByText(/no available times/i)).toBeInTheDocument();
  });
});

describe('SlotPicker', () => {
  afterEach(() => vi.restoreAllMocks());

  it('requests slots with the viewer time zone and a ≤31-day window', async () => {
    const spy = vi.spyOn(api, 'get').mockResolvedValue({ timeZone: 'Asia/Tokyo', slots: [] });
    const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(
      <QueryClientProvider client={qc}>
        <SlotPicker slug="intro-call" timeZone="Asia/Tokyo" days={45} selected={null} onSelect={() => undefined} />
      </QueryClientProvider>,
    );
    expect(await screen.findByText(/no times are available in the next 31 days/i)).toBeInTheDocument();
    const [path, opts] = spy.mock.calls[0]!;
    expect(path).toBe('/booking-pages/public/intro-call/slots');
    const query = (opts as { query: Record<string, string> }).query;
    expect(query.timeZone).toBe('Asia/Tokyo');
    const days = (Date.parse(query.to!) - Date.parse(query.from!)) / 86_400_000;
    expect(days).toBe(30);
  });
});
