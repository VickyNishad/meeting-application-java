import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useNavigate } from 'react-router';
import { Bell, CheckCheck, Loader2 } from 'lucide-react';
import { Button } from '../ui/button';
import { Popover, PopoverContent, PopoverTrigger } from '../ui/overlays';
import { notificationsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import { toastError } from '@/lib/forms';
import { relativeTime } from '@/lib/time';
import { cn } from '@/lib/utils';
import type { Notification } from '@/lib/api/types';
import { useState } from 'react';

export function NotificationsMenu() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);

  const unread = useQuery({
    queryKey: ['notifications', 'unread-count'],
    queryFn: () => notificationsApi.list({ unreadOnly: true, size: 1 }),
    refetchInterval: 60_000,
  });
  const list = useQuery({
    queryKey: ['notifications', 'list'],
    queryFn: () => notificationsApi.list({ size: 15 }),
    enabled: open,
  });

  const invalidate = () => qc.invalidateQueries({ queryKey: ['notifications'] });
  const markRead = useMutation({ mutationFn: (id: string) => notificationsApi.markRead(id), onSuccess: invalidate, onError: (e) => toastError(e) });
  const markAll = useMutation({ mutationFn: () => notificationsApi.markAllRead(), onSuccess: invalidate, onError: (e) => toastError(e) });

  const count = unread.data?.totalItems ?? 0;

  const openItem = (n: Notification) => {
    if (!n.read) markRead.mutate(n.id);
    if (n.link) {
      setOpen(false);
      if (/^https?:\/\//.test(n.link)) {
        try {
          const url = new URL(n.link);
          if (url.origin === window.location.origin) {
            navigate(url.pathname + url.search);
            return;
          }
        } catch {
          // fall through
        }
        window.open(n.link, '_blank', 'noopener');
      } else {
        navigate(n.link);
      }
    }
  };

  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>
        <Button variant="ghost" size="icon" className="relative" aria-label={count ? `Notifications, ${count} unread` : 'Notifications'}>
          <Bell aria-hidden="true" />
          {count > 0 && (
            <span className="absolute right-1 top-1 flex min-w-4 items-center justify-center rounded-full bg-destructive px-1 text-[10px] font-semibold leading-4 text-destructive-foreground">
              {count > 99 ? '99+' : count}
            </span>
          )}
        </Button>
      </PopoverTrigger>
      <PopoverContent align="end" className="w-[min(22rem,calc(100vw-2rem))] p-0">
        <div className="flex items-center justify-between border-b px-3 py-2">
          <h2 className="text-sm font-semibold">Notifications</h2>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => markAll.mutate()}
            loading={markAll.isPending}
            disabled={count === 0}
          >
            <CheckCheck aria-hidden="true" /> Mark all read
          </Button>
        </div>
        <div className="max-h-96 overflow-y-auto">
          {list.isPending && (
            <div className="flex justify-center p-6">
              <Loader2 className="size-5 animate-spin text-muted-foreground" aria-label="Loading" />
            </div>
          )}
          {list.isError && <p className="p-4 text-sm text-destructive">{errorMessage(list.error)}</p>}
          {list.data && list.data.items.length === 0 && (
            <p className="p-6 text-center text-sm text-muted-foreground">You're all caught up.</p>
          )}
          {list.data?.items.map((n) => (
            <button
              key={n.id}
              onClick={() => openItem(n)}
              className={cn(
                'flex w-full flex-col gap-0.5 border-b px-3 py-2.5 text-left last:border-b-0 hover:bg-accent focus-visible:bg-accent focus-visible:outline-none',
                !n.read && 'bg-primary/5',
              )}
            >
              <span className="flex items-center gap-2 text-sm font-medium">
                {!n.read && <span className="size-2 shrink-0 rounded-full bg-primary" aria-label="Unread" />}
                {n.title}
              </span>
              <span className="line-clamp-2 text-xs text-muted-foreground">{n.body}</span>
              <span className="text-[11px] text-muted-foreground">{relativeTime(n.createdAt)}</span>
            </button>
          ))}
        </div>
      </PopoverContent>
    </Popover>
  );
}
