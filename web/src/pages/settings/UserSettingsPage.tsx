import { useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { CalendarDays, LogOut, Monitor, ShieldCheck } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Avatar, Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input } from '@/components/ui/primitives';
import { ConfirmDialog, ErrorState, FormField, ListSkeleton, PageHeader, RootError, TimeZoneSelect } from '@/components/common';
import { api } from '@/lib/api/client';
import { authApi, calendarApi, usersApi } from '@/lib/api/endpoints';
import type { CalendarConnection, CalendarProvider, SessionInfo, UserProfile } from '@/lib/api/types';
import { toastError } from '@/lib/forms';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyApiErrors } from '@/lib/forms';
import { formatDateTime } from '@/lib/time';
import { passwordSchema } from '@/lib/validation';

const profileSchema = z.object({
  displayName: z.string().trim().min(1, 'Name is required').max(100),
  timeZone: z.string().min(1),
  avatarUrl: z.union([z.literal(''), z.url('Enter a valid URL (https://…)')]),
});

function ProfileCard({ user }: { user: UserProfile }) {
  const { setUser } = useAuth();
  const form = useForm<z.infer<typeof profileSchema>>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: user.displayName, timeZone: user.timeZone, avatarUrl: user.avatarUrl ?? '' },
  });
  const e = form.formState.errors;
  const onSubmit = form.handleSubmit(async (v) => {
    try {
      const updated = await usersApi.updateMe({ displayName: v.displayName.trim(), timeZone: v.timeZone, avatarUrl: v.avatarUrl || undefined });
      setUser(updated);
      form.reset({ displayName: updated.displayName, timeZone: updated.timeZone, avatarUrl: updated.avatarUrl ?? '' });
      toast.success('Profile updated');
    } catch (err) {
      applyApiErrors(err, form.setError, ['displayName', 'timeZone', 'avatarUrl'], { toast: false });
    }
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Profile</CardTitle>
        <CardDescription className="flex flex-wrap items-center gap-2">
          {user.email}
          {user.emailVerified ? <Badge variant="success">Verified</Badge> : <Badge variant="warning">Unverified</Badge>}
          {user.systemRole === 'SYSTEM_ADMIN' && <Badge>System admin</Badge>}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="grid gap-4 sm:grid-cols-2" noValidate>
          <div className="flex items-center gap-3 sm:col-span-2">
            <Avatar name={user.displayName} src={form.watch('avatarUrl') || undefined} className="size-14 text-base" />
            <p className="text-sm text-muted-foreground">Member since {formatDateTime(user.createdAt)}</p>
          </div>
          <div className="sm:col-span-2">
            <RootError message={e.root?.server?.message} />
          </div>
          <FormField label="Display name" error={e.displayName?.message}>
            {({ id, describedBy, invalid }) => <Input id={id} aria-describedby={describedBy} aria-invalid={invalid} {...form.register('displayName')} />}
          </FormField>
          <FormField label="Time zone" error={e.timeZone?.message}>
            {({ id, describedBy, invalid }) => (
              <Controller control={form.control} name="timeZone" render={({ field }) => <TimeZoneSelect id={id} value={field.value} onChange={field.onChange} aria-describedby={describedBy} aria-invalid={invalid} />} />
            )}
          </FormField>
          <FormField label="Avatar URL (optional)" error={e.avatarUrl?.message} className="sm:col-span-2">
            {({ id, describedBy, invalid }) => <Input id={id} type="url" placeholder="https://…" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('avatarUrl')} />}
          </FormField>
          <Button type="submit" className="justify-self-start" loading={form.formState.isSubmitting} disabled={!form.formState.isDirty}>
            Save profile
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

const pwSchema = z
  .object({ currentPassword: z.string().min(1, 'Enter your current password'), newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], error: 'Passwords do not match' });

function PasswordCard() {
  const qc = useQueryClient();
  const form = useForm<z.infer<typeof pwSchema>>({ resolver: zodResolver(pwSchema), defaultValues: { currentPassword: '', newPassword: '', confirm: '' } });
  const e = form.formState.errors;
  const onSubmit = form.handleSubmit(async ({ currentPassword, newPassword }) => {
    try {
      await usersApi.changePassword({ currentPassword, newPassword });
      form.reset();
      toast.success('Password changed. Other sessions were signed out.');
      void qc.invalidateQueries({ queryKey: ['sessions'] });
    } catch (err) {
      applyApiErrors(err, form.setError, ['currentPassword', 'newPassword'], { toast: false, aliases: { password: 'currentPassword' } });
    }
  });
  return (
    <Card>
      <CardHeader>
        <CardTitle>Password</CardTitle>
        <CardDescription>Changing your password signs out your other sessions.</CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={onSubmit} className="grid max-w-md gap-4" noValidate>
          <RootError message={e.root?.server?.message} />
          <FormField label="Current password" error={e.currentPassword?.message}>
            {({ id, describedBy, invalid }) => <Input id={id} type="password" autoComplete="current-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('currentPassword')} />}
          </FormField>
          <FormField label="New password" error={e.newPassword?.message} hint="12–128 characters.">
            {({ id, describedBy, invalid }) => <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('newPassword')} />}
          </FormField>
          <FormField label="Confirm new password" error={e.confirm?.message}>
            {({ id, describedBy, invalid }) => <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('confirm')} />}
          </FormField>
          <Button type="submit" className="justify-self-start" loading={form.formState.isSubmitting}>
            Change password
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}

function SessionsCard() {
  const qc = useQueryClient();
  const navigate = useNavigate();
  const q = useQuery({ queryKey: ['sessions'], queryFn: usersApi.sessions });
  const [revoking, setRevoking] = useState<SessionInfo | null>(null);
  const [logoutAllOpen, setLogoutAllOpen] = useState(false);
  return (
    <Card>
      <CardHeader className="flex-row flex-wrap items-start justify-between gap-2">
        <div className="grid gap-1.5">
          <CardTitle>Sessions</CardTitle>
          <CardDescription>Devices currently signed in to your account.</CardDescription>
        </div>
        <Button variant="outline" className="text-destructive" onClick={() => setLogoutAllOpen(true)}>
          <LogOut aria-hidden="true" /> Sign out everywhere
        </Button>
      </CardHeader>
      <CardContent>
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <ul className="divide-y rounded-lg border">
            {q.data.map((s) => (
              <li key={s.id} className="flex flex-wrap items-center gap-3 px-3 py-3 text-sm">
                <Monitor className="size-5 text-muted-foreground" aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-medium" title={s.userAgent}>
                    {s.userAgent || 'Unknown device'}
                  </p>
                  <p className="text-xs text-muted-foreground">
                    {s.ipAddress} · last active {formatDateTime(s.lastUsedAt)} · signed in {formatDateTime(s.createdAt)}
                  </p>
                </div>
                {s.current ? (
                  <Badge variant="success">
                    <ShieldCheck className="size-3" aria-hidden="true" /> This device
                  </Badge>
                ) : (
                  <Button size="sm" variant="outline" onClick={() => setRevoking(s)}>
                    Revoke
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <ConfirmDialog
        open={revoking !== null}
        onOpenChange={(o) => !o && setRevoking(null)}
        title="Revoke this session?"
        description="That device will be signed out."
        confirmLabel="Revoke"
        onConfirm={async () => {
          if (!revoking) return;
          await usersApi.revokeSession(revoking.id);
          toast.success('Session revoked');
          await qc.invalidateQueries({ queryKey: ['sessions'] });
        }}
      />
      <ConfirmDialog
        open={logoutAllOpen}
        onOpenChange={setLogoutAllOpen}
        title="Sign out of all sessions?"
        description="Every device, including this one, will be signed out."
        confirmLabel="Sign out everywhere"
        onConfirm={async () => {
          await authApi.logoutAll();
          api.setSession(null);
          qc.clear();
          navigate('/login', { replace: true });
        }}
      />
    </Card>
  );
}

const CALENDAR_LABEL: Record<CalendarProvider, string> = { GOOGLE: 'Google Calendar', MICROSOFT: 'Microsoft Outlook' };

function CalendarCard() {
  const qc = useQueryClient();
  const q = useQuery({ queryKey: ['calendar-connections'], queryFn: calendarApi.connections });
  const [busy, setBusy] = useState<CalendarProvider | null>(null);
  const [disconnecting, setDisconnecting] = useState<CalendarConnection | null>(null);

  const connect = async (provider: CalendarProvider) => {
    setBusy(provider);
    try {
      const { authorizationUrl } = await calendarApi.authorize(provider);
      // Full-page navigation to the provider consent screen; the backend redirects back to /settings?calendar=…
      window.location.assign(authorizationUrl);
    } catch (e) {
      toastError(e);
      setBusy(null);
    }
  };

  return (
    <Card>
      <CardHeader>
        <CardTitle>Calendar integrations</CardTitle>
        <CardDescription>Connect a calendar to add meetings and bookings to it and to block busy times.</CardDescription>
      </CardHeader>
      <CardContent>
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No calendar providers are available.</p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {q.data.map((c) => (
              <li key={c.provider} className="flex flex-wrap items-center gap-3 px-3 py-3 text-sm">
                <CalendarDays className="size-5 text-muted-foreground" aria-hidden="true" />
                <div className="min-w-0 flex-1">
                  <p className="font-medium">{CALENDAR_LABEL[c.provider] ?? c.provider}</p>
                  <p className="text-xs text-muted-foreground">
                    {c.connected
                      ? `Connected${c.accountEmail ? ` as ${c.accountEmail}` : ''}`
                      : c.available
                        ? 'Not connected'
                        : 'Not configured on server'}
                    {c.status && c.connected && c.status !== 'ACTIVE' && c.status !== 'CONNECTED' && ` · ${c.status.toLowerCase()}`}
                  </p>
                </div>
                {c.connected ? (
                  <Button size="sm" variant="outline" className="text-destructive" onClick={() => setDisconnecting(c)}>
                    Disconnect
                  </Button>
                ) : (
                  <Button
                    size="sm"
                    disabled={!c.available}
                    title={c.available ? undefined : 'Not configured on server'}
                    aria-label={c.available ? `Connect ${CALENDAR_LABEL[c.provider] ?? c.provider}` : `Connect ${CALENDAR_LABEL[c.provider] ?? c.provider} (not configured on server)`}
                    loading={busy === c.provider}
                    onClick={() => void connect(c.provider)}
                  >
                    Connect
                  </Button>
                )}
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      <ConfirmDialog
        open={disconnecting !== null}
        onOpenChange={(o) => !o && setDisconnecting(null)}
        title={`Disconnect ${disconnecting ? (CALENDAR_LABEL[disconnecting.provider] ?? disconnecting.provider) : ''}?`}
        description="New meetings and bookings will no longer be added to this calendar."
        confirmLabel="Disconnect"
        onConfirm={async () => {
          if (!disconnecting) return;
          await calendarApi.disconnect(disconnecting.provider);
          toast.success('Calendar disconnected');
          await qc.invalidateQueries({ queryKey: ['calendar-connections'] });
        }}
      />
    </Card>
  );
}

/** Shows the result of the calendar OAuth round-trip (/settings?calendar=connected|error) once. */
function useCalendarResultToast() {
  const [params, setParams] = useSearchParams();
  const handled = useRef(false);
  const result = params.get('calendar');
  useEffect(() => {
    if (!result || handled.current) return;
    handled.current = true;
    if (result === 'connected') toast.success('Calendar connected');
    else toast.error('The calendar could not be connected. Please try again.');
    const next = new URLSearchParams(params);
    next.delete('calendar');
    setParams(next, { replace: true });
  }, [result, params, setParams]);
}

export default function UserSettingsPage() {
  useCalendarResultToast();
  const { user } = useAuth();
  const me = useQuery({ queryKey: ['me'], queryFn: usersApi.me, initialData: user ?? undefined });
  return (
    <div className="grid gap-6">
      <PageHeader title="Settings" description="Manage your profile, calendars, password and sessions." />
      {me.isError && !me.data ? <ErrorState error={me.error} onRetry={() => void me.refetch()} /> : me.data ? <ProfileCard key={me.data.id + me.data.displayName} user={me.data} /> : <ListSkeleton rows={2} />}
      <CalendarCard />
      <PasswordCard />
      <SessionsCard />
    </div>
  );
}
