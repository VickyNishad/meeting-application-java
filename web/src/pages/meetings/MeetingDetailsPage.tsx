import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Ban, Copy, Film, Mail, Mic, Pencil, Play, Users } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Badge, Card, CardContent, CardDescription, CardHeader, CardTitle, Label, Textarea } from '@/components/ui/primitives';
import { ConfirmDialog, ErrorState, ListSkeleton, MeetingStatusBadge, PageHeader } from '@/components/common';
import { MEETING_TYPE_LABEL, canStartOrJoin, copyJoinLink } from '@/components/meetings';
import { meetingsApi, recordingsApi, speakerStatsApi } from '@/lib/api/endpoints';
import { SpeakingShare } from '@/components/SpeakingShare';
import { recordingSubject } from '@/lib/recordings';
import type { Meeting } from '@/lib/api/types';
import { browserTimeZone, formatDateTime } from '@/lib/time';
import { formatDuration, joinLinkFor } from '@/lib/utils';
import { emailListSchema } from '@/lib/validation';
import { errorMessage } from '@/lib/api/problem';

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="grid grid-cols-[9rem_1fr] gap-2 py-1.5 text-sm">
      <dt className="text-muted-foreground">{label}</dt>
      <dd className="min-w-0 break-words">{children}</dd>
    </div>
  );
}

function Invitations({ meeting }: { meeting: Meeting }) {
  const qc = useQueryClient();
  const [text, setText] = useState('');
  const [error, setError] = useState<string | null>(null);
  const q = useQuery({ queryKey: ['meeting', meeting.id, 'invitations'], queryFn: () => meetingsApi.invitations(meeting.id) });
  const invite = useMutation({
    mutationFn: (emails: string[]) => meetingsApi.invite(meeting.id, emails),
    onSuccess: async (_d, emails) => {
      toast.success(`Invitation${emails.length > 1 ? 's' : ''} sent`);
      setText('');
      await qc.invalidateQueries({ queryKey: ['meeting', meeting.id, 'invitations'] });
    },
    onError: (e) => setError(errorMessage(e)),
  });
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    setError(null);
    const parsed = emailListSchema.safeParse(text);
    if (!parsed.success) return setError(parsed.error.issues[0]?.message ?? 'Invalid emails');
    if (parsed.data.length === 0) return setError('Enter at least one email address');
    invite.mutate(parsed.data);
  };
  const disabled = meeting.status === 'ENDED' || meeting.status === 'CANCELLED';
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Mail className="size-4" aria-hidden="true" /> Invitations
        </CardTitle>
        <CardDescription>Invitees get the join link by email.</CardDescription>
      </CardHeader>
      <CardContent className="grid gap-4">
        {!disabled && (
          <form onSubmit={onSubmit} className="grid gap-2" noValidate>
            <Label htmlFor="invite-emails">Email addresses</Label>
            <Textarea
              id="invite-emails"
              rows={2}
              value={text}
              onChange={(e) => setText(e.target.value)}
              placeholder="name@example.com, other@example.com"
              aria-invalid={!!error}
              aria-describedby={error ? 'invite-err' : undefined}
            />
            {error && (
              <p id="invite-err" role="alert" className="text-xs text-destructive">
                {error}
              </p>
            )}
            <Button type="submit" size="sm" className="justify-self-start" loading={invite.isPending}>
              Send invitations
            </Button>
          </form>
        )}
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No one has been invited yet.</p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {q.data.map((inv) => (
              <li key={inv.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2 text-sm">
                <span className="truncate">{inv.email}</span>
                <span className="flex items-center gap-2 text-xs text-muted-foreground">
                  <Badge variant="secondary">{inv.status}</Badge>
                  {formatDateTime(inv.sentAt)}
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
    </Card>
  );
}

function Attendance({ meeting }: { meeting: Meeting }) {
  const q = useQuery({ queryKey: ['meeting', meeting.id, 'participants'], queryFn: () => meetingsApi.participants(meeting.id) });
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Users className="size-4" aria-hidden="true" /> Participants
        </CardTitle>
      </CardHeader>
      <CardContent>
        {q.isPending ? (
          <ListSkeleton rows={2} />
        ) : q.isError ? (
          <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        ) : q.data.length === 0 ? (
          <p className="text-sm text-muted-foreground">No one has joined yet.</p>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2 pr-3 font-medium">Name</th>
                  <th className="py-2 pr-3 font-medium">Role</th>
                  <th className="py-2 pr-3 font-medium">State</th>
                  <th className="py-2 pr-3 font-medium">Joined</th>
                  <th className="py-2 font-medium">Duration</th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {q.data.map((p) => (
                  <tr key={p.id}>
                    <td className="py-2 pr-3">{p.displayName}</td>
                    <td className="py-2 pr-3">{p.role.replace('_', '-').toLowerCase()}</td>
                    <td className="py-2 pr-3">
                      <Badge variant="secondary">{p.state}</Badge>
                    </td>
                    <td className="py-2 pr-3">{formatDateTime(p.joinedAt)}</td>
                    <td className="py-2">{formatDuration(p.durationSeconds)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </CardContent>
    </Card>
  );
}

function MeetingRecordings({ meeting }: { meeting: Meeting }) {
  const q = useQuery({ queryKey: ['meeting', meeting.id, 'recordings'], queryFn: () => recordingsApi.forMeeting(meeting.id) });
  if (q.isPending || (q.data && q.data.length === 0)) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Film className="size-4" aria-hidden="true" /> Recordings
        </CardTitle>
      </CardHeader>
      <CardContent>
        {q.isError ? (
          <ErrorState error={q.error} />
        ) : (
          <ul className="grid gap-2 text-sm">
            {q.data.map((r) => (
              <li key={r.id} className="flex items-center justify-between gap-2">
                <span>
                  <span className="font-medium">{recordingSubject(r)}</span> · {formatDateTime(r.startedAt)} · {formatDuration(r.durationSeconds)}
                </span>
                <Badge variant={r.status === 'AVAILABLE' ? 'success' : r.status === 'FAILED' ? 'destructive' : 'secondary'}>{r.status}</Badge>
              </li>
            ))}
          </ul>
        )}
        <Button variant="link" className="mt-2 px-0" asChild>
          <Link to="/recordings">Open recording library</Link>
        </Button>
      </CardContent>
    </Card>
  );
}

/** Host only: who spoke how much, over all sessions of the meeting (one row per participation). */
function SpeakingTime({ meeting }: { meeting: Meeting }) {
  const q = useQuery({ queryKey: ['meeting', meeting.id, 'speaker-stats'], queryFn: () => speakerStatsApi.forMeeting(meeting.id) });
  if (q.isPending || (q.data && q.data.speakers.length === 0)) return null;
  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Mic className="size-4" aria-hidden="true" /> Speaking time
        </CardTitle>
        {q.data && <CardDescription>{formatDuration(Math.round(q.data.totalSpeakingMs / 1000))} of speech in total</CardDescription>}
      </CardHeader>
      <CardContent>
        {q.isError ? (
          <ErrorState error={q.error} />
        ) : (
          <SpeakingShare
            label="Speaking time per participant"
            speakers={q.data.speakers.map((s) => ({
              name: s.displayName,
              speakingMs: s.speakingMs,
              sharePercent: q.data.totalSpeakingMs > 0 ? (s.speakingMs * 100) / q.data.totalSpeakingMs : 0,
            }))}
          />
        )}
      </CardContent>
    </Card>
  );
}

export default function MeetingDetailsPage() {
  const { meetingId = '' } = useParams();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const [cancelOpen, setCancelOpen] = useState(false);
  const q = useQuery({ queryKey: ['meeting', meetingId], queryFn: () => meetingsApi.get(meetingId) });

  if (q.isPending) return <ListSkeleton rows={5} />;
  if (q.isError) return <ErrorState error={q.error} onRetry={() => void q.refetch()} />;
  const m = q.data;
  const isHost = m.myRole === 'HOST';
  const tz = browserTimeZone();
  const editable = isHost && (m.status === 'SCHEDULED' || m.status === 'LIVE');

  return (
    <div className="grid gap-6">
      <PageHeader
        title={m.title}
        description={
          <span className="flex flex-wrap items-center gap-2">
            <MeetingStatusBadge status={m.status} />
            <span>{MEETING_TYPE_LABEL[m.type]}</span>
            {m.locked && <Badge variant="warning">Locked</Badge>}
          </span>
        }
        actions={
          <>
            <Button variant="outline" onClick={() => void copyJoinLink(m)}>
              <Copy aria-hidden="true" /> Copy join link
            </Button>
            {editable && (
              <Button variant="outline" asChild>
                <Link to={`/meetings/${m.id}/edit`}>
                  <Pencil aria-hidden="true" /> Edit
                </Link>
              </Button>
            )}
            {isHost && m.status === 'SCHEDULED' && (
              <Button variant="outline" className="text-destructive" onClick={() => setCancelOpen(true)}>
                <Ban aria-hidden="true" /> Cancel meeting
              </Button>
            )}
            {canStartOrJoin(m) && (
              <Button onClick={() => navigate(`/meetings/${m.id}/room`)}>
                <Play aria-hidden="true" /> {m.status === 'LIVE' ? 'Join' : isHost ? 'Start' : 'Join'}
              </Button>
            )}
          </>
        }
      />

      <div className="grid gap-6 lg:grid-cols-[1fr_1fr]">
        <Card>
          <CardHeader>
            <CardTitle>Overview</CardTitle>
          </CardHeader>
          <CardContent>
            {m.description && <p className="mb-3 whitespace-pre-wrap text-sm">{m.description}</p>}
            <dl className="divide-y">
              <Row label="Starts">{m.startTime ? `${formatDateTime(m.startTime, tz)} (your time)` : '—'}</Row>
              <Row label="Ends">{m.endTime ? formatDateTime(m.endTime, tz) : '—'}</Row>
              <Row label="Meeting time zone">{m.timeZone}</Row>
              {m.recurrenceRule && (
                <Row label="Repeats">
                  <code className="text-xs">{m.recurrenceRule}</code>
                </Row>
              )}
              <Row label="Host">{m.host.displayName}</Row>
              <Row label="Join code">
                <code>{m.joinCode}</code>
              </Row>
              <Row label="Join link">
                <span className="break-all text-xs">{joinLinkFor(m.joinCode, m.joinUrl)}</span>
              </Row>
              <Row label="Media">{m.mediaMode === 'AUDIO_ONLY' ? 'Voice only' : 'Video'}</Row>
              <Row label="Access">{{ LINK: 'Anyone with the link', ORGANIZATION: 'Organization members', INVITEES_ONLY: 'Invitees only' }[m.privacy]}</Row>
              <Row label="Password">{m.passwordProtected ? 'Required' : 'None'}</Row>
              <Row label="Waiting room">{m.waitingRoomEnabled ? 'On' : 'Off'}</Row>
              <Row label="Guests">{m.allowGuests ? 'Allowed' : 'Not allowed'}</Row>
              <Row label="Join before host">{m.joinBeforeHost ? 'Yes' : 'No'}</Row>
              <Row label="Mute on entry">{m.muteOnEntry ? 'Yes' : 'No'}</Row>
              <Row label="Record automatically">{m.autoRecord ? 'Yes' : 'No'}</Row>
              <Row label="Max participants">{m.maxParticipants}</Row>
              <Row label="Screen sharing">{m.screenSharePolicy === 'EVERYONE' ? 'Everyone' : 'Hosts only'}</Row>
              <Row label="Who can see whom">{m.visibilityMode === 'HOST_AND_SELF_ONLY' ? 'Host and self only — attendees see only the host, co-hosts and themselves' : 'Everyone visible'}</Row>
            </dl>
          </CardContent>
        </Card>
        <div className="grid content-start gap-6">
          {isHost && <Invitations meeting={m} />}
          {(m.status === 'LIVE' || m.status === 'ENDED') && <Attendance meeting={m} />}
          <MeetingRecordings meeting={m} />
          {isHost && (m.status === 'LIVE' || m.status === 'ENDED' || m.type === 'PERSONAL_ROOM') && <SpeakingTime meeting={m} />}
        </div>
      </div>

      <ConfirmDialog
        open={cancelOpen}
        onOpenChange={setCancelOpen}
        title="Cancel this meeting?"
        description="Invitees will be notified. This can't be undone."
        confirmLabel="Cancel meeting"
        withReason
        onConfirm={async (reason) => {
          await meetingsApi.cancel(m.id, reason);
          toast.success('Meeting cancelled');
          await qc.invalidateQueries({ queryKey: ['meetings'] });
          await qc.invalidateQueries({ queryKey: ['meeting', m.id] });
        }}
      />
    </div>
  );
}
