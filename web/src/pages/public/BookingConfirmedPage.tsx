import { Link, useLocation, useParams } from 'react-router';
import { CalendarCheck2, Copy, Video } from 'lucide-react';
import { toast } from 'sonner';
import { Button } from '@/components/ui/button';
import { Alert, Card, CardContent } from '@/components/ui/primitives';
import type { BookingConfirmation } from '@/lib/api/types';
import { browserTimeZone, formatDateTime, formatTime } from '@/lib/time';
import { appOrigin, copyToClipboard } from '@/lib/utils';
import { toastError } from '@/lib/forms';

export default function BookingConfirmedPage() {
  const { slug = '' } = useParams();
  const location = useLocation();
  const state = location.state as { confirmation?: BookingConfirmation; timeZone?: string } | null;
  const c = state?.confirmation;
  const tz = state?.timeZone ?? browserTimeZone();

  if (!c) {
    return (
      <Card className="mx-auto mt-12 w-full max-w-md">
        <CardContent className="grid gap-3 p-8 text-center">
          <p>This confirmation is no longer available on this page. Check your email for the booking details and the link to manage it.</p>
          <Button asChild variant="outline">
            <Link to={`/book/${slug}`}>Book another time</Link>
          </Button>
        </CardContent>
      </Card>
    );
  }
  const manageUrl = `${appOrigin()}/bookings/manage/${encodeURIComponent(c.manageToken)}`;
  return (
    <Card className="mx-auto mt-8 w-full max-w-lg">
      <CardContent className="grid gap-5 p-8">
        <div className="flex flex-col items-center gap-2 text-center">
          <CalendarCheck2 className="size-12 text-success" aria-hidden="true" />
          <h1 className="text-xl font-semibold">You're booked!</h1>
          <p className="text-sm text-muted-foreground">A confirmation and calendar invitation were sent to {c.inviteeEmail}.</p>
        </div>
        <div className="rounded-lg border p-4 text-sm">
          <p className="font-medium">{c.bookingPageTitle}</p>
          <p className="mt-1">
            {formatDateTime(c.startTime, tz)} – {formatTime(c.endTime, tz)}
          </p>
          <p className="text-xs text-muted-foreground">{tz}</p>
        </div>
        {c.joinUrl && (
          <Button asChild>
            <a href={c.joinUrl}>
              <Video aria-hidden="true" /> Meeting link
            </a>
          </Button>
        )}
        <Alert>
          <div className="grid w-full gap-2">
            <p className="font-medium">Manage your booking</p>
            <p className="text-muted-foreground">Keep this private link to reschedule or cancel. It is shown only once.</p>
            <code className="break-all rounded bg-background p-2 text-xs">{manageUrl}</code>
            <div className="flex flex-wrap gap-2">
              <Button size="sm" variant="outline" onClick={() => copyToClipboard(manageUrl).then(() => toast.success('Link copied'), toastError)}>
                <Copy aria-hidden="true" /> Copy link
              </Button>
              <Button size="sm" variant="outline" asChild>
                <Link to={`/bookings/manage/${encodeURIComponent(c.manageToken)}`}>Open</Link>
              </Button>
            </div>
          </div>
        </Alert>
      </CardContent>
    </Card>
  );
}
