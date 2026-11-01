import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Activity, Building2, CalendarCheck, HardDrive, Radio, Search, Users, Video } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardHeader, CardTitle, Input, Label, NativeSelect, Skeleton } from '@/components/ui/primitives';
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/components/ui/overlays';
import { ConfirmDialog, EmptyState, ErrorState, ListSkeleton, PageHeader, Pagination } from '@/components/common';
import { adminApi } from '@/lib/api/endpoints';
import type { UserProfile } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { toastError } from '@/lib/forms';
import { formatDate, formatDateTime } from '@/lib/time';
import { formatBytes } from '@/lib/utils';

function Stats() {
  const q = useQuery({ queryKey: ['admin', 'stats'], queryFn: adminApi.stats, refetchInterval: 30_000 });
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const s = q.data;
  const items = [
    { label: 'Users', value: s?.users, icon: Users },
    { label: 'Organizations', value: s?.organizations, icon: Building2 },
    { label: 'Meetings today', value: s?.meetingsToday, icon: Video },
    { label: 'Live meetings', value: s?.liveMeetings, icon: Radio },
    { label: 'Live participants', value: s?.liveParticipants, icon: Activity },
    { label: 'Bookings today', value: s?.bookingsToday, icon: CalendarCheck },
    { label: 'Recording storage', value: s ? formatBytes(s.recordingsStorageBytes) : undefined, icon: HardDrive },
  ];
  return (
    <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
      {items.map((it) => (
        <Card key={it.label} className="p-4">
          <p className="flex items-center gap-2 text-xs font-medium text-muted-foreground">
            <it.icon className="size-4" aria-hidden="true" /> {it.label}
          </p>
          {q.isPending ? <Skeleton className="mt-2 h-7 w-16" /> : <p className="mt-1 text-2xl font-semibold tabular-nums">{it.value ?? '—'}</p>}
        </Card>
      ))}
    </div>
  );
}

function UsersTable() {
  const qc = useQueryClient();
  const { user: me } = useAuth();
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [disabling, setDisabling] = useState<UserProfile | null>(null);
  const q = useQuery({ queryKey: ['admin', 'users', query, page], queryFn: () => adminApi.users(query, page, 20), placeholderData: keepPreviousData });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: string; body: Parameters<typeof adminApi.updateUser>[1] }) => adminApi.updateUser(id, body),
    onSuccess: () => {
      toast.success('User updated');
      void qc.invalidateQueries({ queryKey: ['admin', 'users'] });
    },
    onError: (e) => toastError(e),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setPage(0);
    setQuery(search.trim());
  };
  return (
    <div className="grid gap-4">
      <form onSubmit={submit} className="flex max-w-md gap-2" role="search">
        <Label htmlFor="user-search" className="sr-only">
          Search users
        </Label>
        <Input id="user-search" placeholder="Search by name or email" value={search} onChange={(e) => setSearch(e.target.value)} />
        <Button type="submit" variant="secondary">
          <Search aria-hidden="true" /> Search
        </Button>
      </form>
      {q.isPending ? (
        <ListSkeleton rows={4} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.items.length === 0 ? (
        <EmptyState icon={Users} title="No users found" />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">User</th>
                  <th className="px-3 py-2 font-medium">Status</th>
                  <th className="px-3 py-2 font-medium">Role</th>
                  <th className="px-3 py-2 font-medium">Created</th>
                  <th className="px-3 py-2">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {q.data.items.map((u) => {
                  const self = u.id === me?.id;
                  return (
                    <tr key={u.id}>
                      <td className="px-3 py-2">
                        <p className="font-medium">{u.displayName}</p>
                        <p className="text-xs text-muted-foreground">
                          {u.email} {!u.emailVerified && '· unverified'}
                        </p>
                      </td>
                      <td className="px-3 py-2">
                        <Badge variant={u.status === 'ACTIVE' ? 'success' : 'destructive'}>{u.status.toLowerCase()}</Badge>
                      </td>
                      <td className="px-3 py-2">
                        <NativeSelect
                          aria-label={`System role of ${u.displayName}`}
                          className="h-8 w-40"
                          value={u.systemRole}
                          disabled={self || update.isPending}
                          onChange={(e) => update.mutate({ id: u.id, body: { systemRole: e.target.value as UserProfile['systemRole'] } })}
                        >
                          <option value="USER">User</option>
                          <option value="SYSTEM_ADMIN">System admin</option>
                        </NativeSelect>
                      </td>
                      <td className="px-3 py-2 whitespace-nowrap">{formatDate(u.createdAt)}</td>
                      <td className="px-3 py-2 text-right">
                        {u.status === 'ACTIVE' ? (
                          <Button size="sm" variant="outline" className="text-destructive" disabled={self} onClick={() => setDisabling(u)}>
                            Disable
                          </Button>
                        ) : (
                          <Button size="sm" variant="outline" disabled={update.isPending} onClick={() => update.mutate({ id: u.id, body: { status: 'ACTIVE' } })}>
                            Enable
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
        </>
      )}
      <ConfirmDialog
        open={disabling !== null}
        onOpenChange={(o) => !o && setDisabling(null)}
        title={`Disable ${disabling?.displayName ?? ''}?`}
        description="They are signed out of every session and can't sign in until re-enabled."
        confirmLabel="Disable account"
        onConfirm={async () => {
          if (!disabling) return;
          await adminApi.updateUser(disabling.id, { status: 'DISABLED' });
          toast.success('Account disabled');
          await qc.invalidateQueries({ queryKey: ['admin', 'users'] });
        }}
      />
    </div>
  );
}

function LiveMeetings() {
  const q = useQuery({ queryKey: ['admin', 'live'], queryFn: adminApi.liveMeetings, refetchInterval: 15_000 });
  if (q.isPending) return <ListSkeleton rows={2} />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  if (q.data.length === 0) return <EmptyState icon={Radio} title="No live meetings" description="Meetings in progress appear here." />;
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 font-medium">Meeting</th>
            <th className="px-3 py-2 font-medium">Host</th>
            <th className="px-3 py-2 font-medium">Participants</th>
            <th className="px-3 py-2 font-medium">Started</th>
          </tr>
        </thead>
        <tbody className="divide-y">
          {q.data.map((m) => (
            <tr key={m.id}>
              <td className="px-3 py-2">
                <Link to={`/meetings/${m.id}`} className="font-medium hover:underline">
                  {m.title}
                </Link>
                {m.locked && <Badge variant="warning" className="ml-2">Locked</Badge>}
              </td>
              <td className="px-3 py-2">{m.host.displayName}</td>
              <td className="px-3 py-2 tabular-nums">
                {m.participantCount} / {m.maxParticipants}
              </td>
              <td className="px-3 py-2">{formatDateTime(m.startTime ?? m.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function AuditLogs() {
  const [draft, setDraft] = useState({ actorId: '', action: '', from: '', to: '' });
  const [filters, setFilters] = useState(draft);
  const [page, setPage] = useState(0);
  const toInstant = (d: string, endOfDay: boolean) => (d ? new Date(`${d}T${endOfDay ? '23:59:59' : '00:00:00'}`).toISOString() : undefined);
  const q = useQuery({
    queryKey: ['admin', 'audit', filters, page],
    queryFn: () =>
      adminApi.auditLogs({
        actorId: filters.actorId || undefined,
        action: filters.action || undefined,
        from: toInstant(filters.from, false),
        to: toInstant(filters.to, true),
        page,
        size: 25,
      }),
    placeholderData: keepPreviousData,
  });
  return (
    <div className="grid gap-4">
      <form
        className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5"
        onSubmit={(e) => {
          e.preventDefault();
          setPage(0);
          setFilters({ ...draft, actorId: draft.actorId.trim(), action: draft.action.trim() });
        }}
      >
        <div className="grid gap-1.5">
          <Label htmlFor="audit-actor">Actor ID</Label>
          <Input id="audit-actor" placeholder="UUID" value={draft.actorId} onChange={(e) => setDraft({ ...draft, actorId: e.target.value })} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-action">Action</Label>
          <Input id="audit-action" placeholder="e.g. MEETING_ENDED" value={draft.action} onChange={(e) => setDraft({ ...draft, action: e.target.value })} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-from">From</Label>
          <Input id="audit-from" type="date" value={draft.from} onChange={(e) => setDraft({ ...draft, from: e.target.value })} />
        </div>
        <div className="grid gap-1.5">
          <Label htmlFor="audit-to">To</Label>
          <Input id="audit-to" type="date" value={draft.to} onChange={(e) => setDraft({ ...draft, to: e.target.value })} />
        </div>
        <div className="flex items-end gap-2">
          <Button type="submit">Apply</Button>
          <Button
            type="button"
            variant="ghost"
            onClick={() => {
              const empty = { actorId: '', action: '', from: '', to: '' };
              setDraft(empty);
              setFilters(empty);
              setPage(0);
            }}
          >
            Reset
          </Button>
        </div>
      </form>
      {q.isPending ? (
        <ListSkeleton rows={4} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.items.length === 0 ? (
        <EmptyState icon={Activity} title="No audit events match these filters" />
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">Time</th>
                  <th className="px-3 py-2 font-medium">Actor</th>
                  <th className="px-3 py-2 font-medium">Action</th>
                  <th className="px-3 py-2 font-medium">Resource</th>
                  <th className="px-3 py-2 font-medium">Details</th>
                </tr>
              </thead>
              <tbody className="divide-y align-top">
                {q.data.items.map((a) => (
                  <tr key={a.id}>
                    <td className="whitespace-nowrap px-3 py-2">{formatDateTime(a.createdAt)}</td>
                    <td className="px-3 py-2">
                      {a.actor ? (
                        <button type="button" className="text-left hover:underline" title="Filter by this actor" onClick={() => { const f = { ...filters, actorId: a.actor!.id }; setDraft(f); setFilters(f); setPage(0); }}>
                          {a.actor.displayName}
                          <span className="block text-xs text-muted-foreground">{a.actor.email}</span>
                        </button>
                      ) : (
                        <span className="text-muted-foreground">system</span>
                      )}
                      {a.ipAddress && <span className="block text-xs text-muted-foreground">{a.ipAddress}</span>}
                    </td>
                    <td className="px-3 py-2">
                      <code className="text-xs">{a.action}</code>
                    </td>
                    <td className="px-3 py-2 text-xs">
                      {a.resourceType}
                      <span className="block break-all text-muted-foreground">{a.resourceId}</span>
                    </td>
                    <td className="max-w-xs px-3 py-2">
                      <pre className="max-h-24 overflow-auto whitespace-pre-wrap break-all text-[11px] text-muted-foreground">{JSON.stringify(a.details ?? {}, null, 0)}</pre>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pagination page={q.data.page} totalPages={q.data.totalPages} onPageChange={setPage} />
        </>
      )}
    </div>
  );
}

export default function AdminPage() {
  return (
    <div className="grid gap-6">
      <PageHeader title="Administration" description="Platform health, users and audit trail." />
      <Stats />
      <Card>
        <CardHeader className="pb-0">
          <CardTitle className="sr-only">Admin sections</CardTitle>
        </CardHeader>
        <CardContent className="pt-5">
          <Tabs defaultValue="users">
            <TabsList>
              <TabsTrigger value="users">Users & roles</TabsTrigger>
              <TabsTrigger value="live">Live meetings</TabsTrigger>
              <TabsTrigger value="audit">Audit log</TabsTrigger>
            </TabsList>
            <TabsContent value="users">
              <UsersTable />
            </TabsContent>
            <TabsContent value="live">
              <LiveMeetings />
            </TabsContent>
            <TabsContent value="audit">
              <AuditLogs />
            </TabsContent>
          </Tabs>
        </CardContent>
      </Card>
    </div>
  );
}
