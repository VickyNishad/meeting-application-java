import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { Building2, Plus, Trash2, UserPlus } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Input, Label, NativeSelect, Switch } from '@/components/ui/primitives';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/overlays';
import { ConfirmDialog, EmptyState, ErrorState, FormField, ListSkeleton, PageHeader, RootError } from '@/components/common';
import { orgsApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import type { OrgMember, OrgRole, Organization } from '@/lib/api/types';
import { useAuth } from '@/lib/auth/AuthProvider';
import { applyApiErrors, toastError } from '@/lib/forms';
import { formatDate } from '@/lib/time';

const createSchema = z.object({
  name: z.string().trim().min(1, 'Name is required').max(200),
  slug: z.string().regex(/^[a-z0-9-]{3,60}$/, '3–60 lowercase letters, digits or dashes'),
});

function CreateOrgDialog({ open, onOpenChange, onCreated }: { open: boolean; onOpenChange: (o: boolean) => void; onCreated: (o: Organization) => void }) {
  const qc = useQueryClient();
  const form = useForm<z.infer<typeof createSchema>>({ resolver: zodResolver(createSchema), defaultValues: { name: '', slug: '' } });
  const e = form.formState.errors;
  const onSubmit = form.handleSubmit(async (v) => {
    try {
      const org = await orgsApi.create(v);
      toast.success('Organization created');
      await qc.invalidateQueries({ queryKey: ['organizations'] });
      form.reset();
      onOpenChange(false);
      onCreated(org);
    } catch (err) {
      applyApiErrors(err, form.setError, ['name', 'slug'], { toast: false });
    }
  });
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Create organization</DialogTitle>
          <DialogDescription>You'll become its administrator.</DialogDescription>
        </DialogHeader>
        <form onSubmit={onSubmit} className="grid gap-4" noValidate>
          <RootError message={e.root?.server?.message} />
          <FormField label="Name" error={e.name?.message}>
            {({ id, describedBy, invalid }) => (
              <Input
                id={id}
                aria-describedby={describedBy}
                aria-invalid={invalid}
                {...form.register('name', {
                  onChange: (ev: React.ChangeEvent<HTMLInputElement>) => {
                    if (!form.formState.dirtyFields.slug) {
                      form.setValue('slug', ev.target.value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60));
                    }
                  },
                })}
              />
            )}
          </FormField>
          <FormField label="Slug" error={e.slug?.message}>
            {({ id, describedBy, invalid }) => <Input id={id} className="font-mono" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('slug')} />}
          </FormField>
          <DialogFooter>
            <Button type="button" variant="outline" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" loading={form.formState.isSubmitting}>
              Create
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  );
}

function OrgSettings({ org }: { org: Organization }) {
  const qc = useQueryClient();
  const isAdmin = org.myRole === 'ORGANIZATION_ADMIN';
  const [name, setName] = useState(org.name);
  const [retention, setRetention] = useState(String(org.defaultRecordingRetentionDays));
  const [allowGuests, setAllowGuests] = useState(org.allowGuests);
  const [error, setError] = useState<string | null>(null);
  const save = useMutation({
    mutationFn: () => orgsApi.update(org.id, { name: name.trim(), defaultRecordingRetentionDays: Number(retention), allowGuests, version: org.version }),
    onSuccess: async () => {
      toast.success('Organization updated');
      await qc.invalidateQueries({ queryKey: ['organizations'] });
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const submit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const r = Number(retention);
    if (!name.trim()) return setError('Name is required');
    if (!Number.isInteger(r) || r < 1) return setError('Retention must be a whole number of days (≥ 1)');
    save.mutate();
  };
  return (
    <Card>
      <CardHeader>
        <CardTitle>General</CardTitle>
        <CardDescription>
          Slug <code>{org.slug}</code> · created {formatDate(org.createdAt)}
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form onSubmit={submit} className="grid max-w-lg gap-4" noValidate>
          <div className="grid gap-1.5">
            <Label htmlFor="org-name">Name</Label>
            <Input id="org-name" value={name} disabled={!isAdmin} onChange={(e) => setName(e.target.value)} />
          </div>
          <div className="grid gap-1.5">
            <Label htmlFor="org-ret">Default recording retention (days)</Label>
            <Input id="org-ret" type="number" min={1} value={retention} disabled={!isAdmin} onChange={(e) => setRetention(e.target.value)} />
          </div>
          <div className="flex items-center gap-3">
            <Switch id="org-guests" checked={allowGuests} disabled={!isAdmin} onCheckedChange={setAllowGuests} />
            <Label htmlFor="org-guests">Allow guests in organization meetings</Label>
          </div>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          {isAdmin ? (
            <Button type="submit" className="justify-self-start" loading={save.isPending}>
              Save
            </Button>
          ) : (
            <p className="text-sm text-muted-foreground">Only organization admins can change these settings.</p>
          )}
        </form>
      </CardContent>
    </Card>
  );
}

function Members({ org }: { org: Organization }) {
  const qc = useQueryClient();
  const { user } = useAuth();
  const isAdmin = org.myRole === 'ORGANIZATION_ADMIN';
  const q = useQuery({ queryKey: ['org-members', org.id], queryFn: () => orgsApi.members(org.id) });
  const [email, setEmail] = useState('');
  const [role, setRole] = useState<OrgRole>('MEMBER');
  const [error, setError] = useState<string | null>(null);
  const [removing, setRemoving] = useState<OrgMember | null>(null);
  const refresh = () => qc.invalidateQueries({ queryKey: ['org-members', org.id] });
  const add = useMutation({
    mutationFn: () => orgsApi.addMember(org.id, { email: email.trim(), role }),
    onSuccess: () => {
      toast.success('Member added');
      setEmail('');
      void refresh();
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const changeRole = useMutation({
    mutationFn: ({ userId, r }: { userId: string; r: OrgRole }) => orgsApi.updateMember(org.id, userId, r),
    onSuccess: () => {
      toast.success('Role updated');
      void refresh();
    },
    onError: (e) => {
      toastError(e);
      void refresh();
    },
  });

  return (
    <Card>
      <CardHeader>
        <CardTitle>Members</CardTitle>
        <CardDescription>People must already have an account to be added.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {isAdmin && (
          <form
            className="flex flex-col gap-2 sm:flex-row"
            noValidate
            onSubmit={(e) => {
              e.preventDefault();
              setError(null);
              if (!z.email().safeParse(email.trim()).success) return setError('Enter a valid email address');
              add.mutate();
            }}
          >
            <Label htmlFor="member-email" className="sr-only">
              Email
            </Label>
            <Input id="member-email" type="email" placeholder="colleague@company.com" value={email} onChange={(e) => setEmail(e.target.value)} aria-invalid={!!error} />
            <Label htmlFor="member-role" className="sr-only">
              Role
            </Label>
            <NativeSelect id="member-role" className="sm:w-44" value={role} onChange={(e) => setRole(e.target.value as OrgRole)}>
              <option value="MEMBER">Member</option>
              <option value="ORGANIZATION_ADMIN">Admin</option>
            </NativeSelect>
            <Button type="submit" loading={add.isPending}>
              <UserPlus aria-hidden="true" /> Add
            </Button>
          </form>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <table className="w-full text-sm">
              <thead className="bg-muted/50 text-left text-xs text-muted-foreground">
                <tr>
                  <th className="px-3 py-2 font-medium">Name</th>
                  <th className="px-3 py-2 font-medium">Email</th>
                  <th className="px-3 py-2 font-medium">Role</th>
                  <th className="px-3 py-2 font-medium">Joined</th>
                  <th className="px-3 py-2">
                    <span className="sr-only">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {q.data.map((m) => {
                  const self = m.user.id === user?.id;
                  return (
                    <tr key={m.user.id}>
                      <td className="px-3 py-2">
                        {m.user.displayName} {self && <span className="text-muted-foreground">(you)</span>}
                      </td>
                      <td className="px-3 py-2">{m.user.email}</td>
                      <td className="px-3 py-2">
                        {isAdmin ? (
                          <NativeSelect
                            aria-label={`Role of ${m.user.displayName}`}
                            className="h-8 w-36"
                            value={m.role}
                            disabled={changeRole.isPending}
                            onChange={(e) => changeRole.mutate({ userId: m.user.id, r: e.target.value as OrgRole })}
                          >
                            <option value="MEMBER">Member</option>
                            <option value="ORGANIZATION_ADMIN">Admin</option>
                          </NativeSelect>
                        ) : (
                          <Badge variant="secondary">{m.role === 'ORGANIZATION_ADMIN' ? 'Admin' : 'Member'}</Badge>
                        )}
                      </td>
                      <td className="px-3 py-2">{formatDate(m.joinedAt)}</td>
                      <td className="px-3 py-2 text-right">
                        {(isAdmin || self) && (
                          <Button size="icon" variant="ghost" className="text-destructive" aria-label={self ? 'Leave organization' : `Remove ${m.user.displayName}`} onClick={() => setRemoving(m)}>
                            <Trash2 aria-hidden="true" />
                          </Button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
      <ConfirmDialog
        open={removing !== null}
        onOpenChange={(o) => !o && setRemoving(null)}
        title={removing?.user.id === user?.id ? 'Leave this organization?' : `Remove ${removing?.user.displayName ?? ''}?`}
        confirmLabel={removing?.user.id === user?.id ? 'Leave' : 'Remove'}
        onConfirm={async () => {
          if (!removing) return;
          await orgsApi.removeMember(org.id, removing.user.id);
          toast.success('Done');
          await refresh();
          if (removing.user.id === user?.id) await qc.invalidateQueries({ queryKey: ['organizations'] });
        }}
      />
    </Card>
  );
}

export default function OrganizationPage() {
  const q = useQuery({ queryKey: ['organizations'], queryFn: orgsApi.list });
  const [selected, setSelected] = useState<string | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const org = q.data?.find((o) => o.id === selected) ?? q.data?.[0];

  return (
    <div className="grid gap-6">
      <PageHeader
        title="Organization"
        description="Manage your organizations, members and roles."
        actions={
          <Button onClick={() => setCreateOpen(true)}>
            <Plus aria-hidden="true" /> New organization
          </Button>
        }
      />
      {q.isPending ? (
        <ListSkeleton rows={3} />
      ) : q.isError ? (
        <ErrorState error={q.error} onRetry={() => void q.refetch()} />
      ) : q.data.length === 0 ? (
        <EmptyState icon={Building2} title="You're not in an organization" description="Create one to share meetings, guest policies and recording retention with your team." action={<Button onClick={() => setCreateOpen(true)}>Create organization</Button>} />
      ) : (
        <>
          {q.data.length > 1 && (
            <div className="grid max-w-sm gap-1.5">
              <Label htmlFor="org-select">Organization</Label>
              <NativeSelect id="org-select" value={org?.id ?? ''} onChange={(e) => setSelected(e.target.value)}>
                {q.data.map((o) => (
                  <option key={o.id} value={o.id}>
                    {o.name}
                  </option>
                ))}
              </NativeSelect>
            </div>
          )}
          {org && (
            <>
              <OrgSettings key={`${org.id}-${org.version}`} org={org} />
              <Members org={org} />
            </>
          )}
        </>
      )}
      <CreateOrgDialog open={createOpen} onOpenChange={setCreateOpen} onCreated={(o) => setSelected(o.id)} />
    </div>
  );
}
