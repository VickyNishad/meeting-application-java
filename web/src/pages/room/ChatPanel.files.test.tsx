import { beforeEach, describe, expect, it, vi } from 'vitest';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { ChatPanel } from './ChatPanel';
import { MeetingRoomContext, type MeetingRoomState } from './roomContext';
import { chatApi } from '@/lib/api/endpoints';
import type { MeetingChat } from './useMeetingChat';

vi.mock('@/lib/api/endpoints', () => ({
  chatApi: { files: vi.fn(), fileUrl: vi.fn(), upload: vi.fn(), deleteFile: vi.fn() },
}));

const files = [
  { id: 'mine', fileName: 'mine.txt', contentType: 'text/plain', sizeBytes: 5, uploadedBy: 'Me', uploadedByParticipantId: 'p1', createdAt: '2026-01-01T00:00:00Z' },
  { id: 'theirs', fileName: 'theirs.txt', contentType: 'text/plain', sizeBytes: 5, uploadedBy: 'Sam', uploadedByParticipantId: 'p2', createdAt: '2026-01-01T00:00:00Z' },
];

async function openFiles(isModerator: boolean) {
  const room = { meeting: { id: 'm1' }, join: { participantId: 'p1' }, isModerator, chatEnabled: true, stageOnly: false };
  const chat = { messages: [], send: vi.fn() } as unknown as MeetingChat;
  render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MeetingRoomContext.Provider value={room as unknown as MeetingRoomState}>
        <ChatPanel chat={chat} onClose={() => {}} />
      </MeetingRoomContext.Provider>
    </QueryClientProvider>,
  );
  const tab = screen.getByRole('tab', { name: 'Files' });
  fireEvent.mouseDown(tab);
  fireEvent.click(tab);
  await screen.findByText('mine.txt');
}

describe('shared files panel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    Element.prototype.scrollIntoView = vi.fn();
    vi.mocked(chatApi.files).mockResolvedValue(files);
    vi.mocked(chatApi.deleteFile).mockResolvedValue(undefined);
  });

  it('offers delete only on your own files', async () => {
    await openFiles(false);
    expect(screen.getByRole('button', { name: 'Delete mine.txt' })).toBeTruthy();
    expect(screen.queryByRole('button', { name: 'Delete theirs.txt' })).toBeNull();
  });

  it('offers a moderator delete on every file', async () => {
    await openFiles(true);
    expect(screen.getByRole('button', { name: 'Delete theirs.txt' })).toBeTruthy();
  });

  it('asks first, then deletes and refreshes the list', async () => {
    await openFiles(false);
    fireEvent.click(screen.getByRole('button', { name: 'Delete mine.txt' }));
    expect(chatApi.deleteFile).not.toHaveBeenCalled();
    fireEvent.click(await screen.findByRole('button', { name: 'Delete' }));
    await waitFor(() => expect(chatApi.deleteFile).toHaveBeenCalledWith('m1', 'mine', 'p1', undefined));
    await waitFor(() => expect(chatApi.files).toHaveBeenCalledTimes(2));
  });
});
