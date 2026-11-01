import { useId, useMemo, useState, type ReactNode } from 'react';
import { AlertTriangle, ChevronLeft, ChevronRight, RefreshCw, type LucideIcon } from 'lucide-react';
import { Button } from './ui/button';
import {
  AlertDialog,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogTitle,
} from './ui/overlays';
import { Badge, Label, NativeSelect, Skeleton, Textarea } from './ui/primitives';
import { errorMessage, isApiError } from '@/lib/api/problem';
import { listTimeZones } from '@/lib/time';
import type { MeetingStatus } from '@/lib/api/types';
import { cn } from '@/lib/utils';

export function PageHeader({
  title,
  description,
  actions,
}: {
  title: string;
  description?: ReactNode;
  actions?: ReactNode;
}) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-end sm:justify-between">
      <div className="min-w-0">
        <h1 className="text-2xl font-semibold tracking-tight">{title}</h1>
        {description && <p className="mt-1 text-sm text-muted-foreground">{description}</p>}
      </div>
      {actions && <div className="flex flex-wrap gap-2">{actions}</div>}
    </div>
  );
}

export function EmptyState({
  icon: Icon,
  title,
  description,
  action,
  className,
}: {
  icon: LucideIcon;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn('flex flex-col items-center justify-center rounded-xl border border-dashed px-6 py-12 text-center', className)}>
      <div className="mb-3 flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary">
        <Icon className="size-6" aria-hidden="true" />
      </div>
      <h3 className="font-semibold">{title}</h3>
      {description && <p className="mt-1 max-w-sm text-sm text-muted-foreground">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

export function ErrorState({ error, onRetry, className }: { error: unknown; onRetry?: () => void; className?: string }) {
  const code = isApiError(error) ? error.code : undefined;
  return (
    <div role="alert" className={cn('flex flex-col items-center rounded-xl border border-destructive/30 bg-destructive/5 px-6 py-10 text-center', className)}>
      <AlertTriangle className="mb-2 size-6 text-destructive" aria-hidden="true" />
      <p className="font-medium">{errorMessage(error)}</p>
      {code && <p className="mt-1 font-mono text-xs text-muted-foreground">{code}</p>}
      {onRetry && (
        <Button variant="outline" size="sm" className="mt-4" onClick={onRetry}>
          <RefreshCw aria-hidden="true" /> Try again
        </Button>
      )}
    </div>
  );
}

export function ListSkeleton({ rows = 3, className }: { rows?: number; className?: string }) {
  return (
    <div className={cn('space-y-3', className)} role="status" aria-label="Loading">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-16 w-full rounded-xl" />
      ))}
    </div>
  );
}

export function FieldError({ id, message }: { id?: string; message?: string }) {
  if (!message) return null;
  return (
    <p id={id} className="text-xs font-medium text-destructive">
      {message}
    </p>
  );
}

/** Label + control + hint/error wrapper. `children` receives ids for aria wiring. */
export function FormField({
  label,
  error,
  hint,
  children,
  className,
}: {
  label: ReactNode;
  error?: string;
  hint?: ReactNode;
  children: (ids: { id: string; describedBy?: string; invalid: boolean }) => ReactNode;
  className?: string;
}) {
  const id = useId();
  const errId = `${id}-err`;
  const hintId = `${id}-hint`;
  const describedBy = [error ? errId : null, hint ? hintId : null].filter(Boolean).join(' ') || undefined;
  return (
    <div className={cn('grid gap-1.5', className)}>
      <Label htmlFor={id}>{label}</Label>
      {children({ id, describedBy, invalid: !!error })}
      {hint && !error && (
        <p id={hintId} className="text-xs text-muted-foreground">
          {hint}
        </p>
      )}
      <FieldError id={errId} message={error} />
    </div>
  );
}

export function RootError({ message }: { message?: string }) {
  if (!message) return null;
  return (
    <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive">
      {message}
    </div>
  );
}

export function TimeZoneSelect({
  value,
  onChange,
  id,
  className,
  ...rest
}: {
  value: string;
  onChange: (tz: string) => void;
  id?: string;
  className?: string;
  'aria-describedby'?: string;
  'aria-invalid'?: boolean;
  'aria-label'?: string;
}) {
  const zones = useMemo(() => {
    const list = listTimeZones();
    return value && !list.includes(value) ? [value, ...list] : list;
  }, [value]);
  return (
    <NativeSelect id={id} value={value} onChange={(e) => onChange(e.target.value)} className={className} {...rest}>
      {zones.map((z) => (
        <option key={z} value={z}>
          {z.replace(/_/g, ' ')}
        </option>
      ))}
    </NativeSelect>
  );
}

export function Pagination({
  page,
  totalPages,
  onPageChange,
}: {
  page: number;
  totalPages: number;
  onPageChange: (p: number) => void;
}) {
  if (totalPages <= 1) return null;
  return (
    <nav className="mt-4 flex items-center justify-between gap-2" aria-label="Pagination">
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onPageChange(page - 1)}>
        <ChevronLeft aria-hidden="true" /> Previous
      </Button>
      <span className="text-sm text-muted-foreground">
        Page {page + 1} of {totalPages}
      </span>
      <Button variant="outline" size="sm" disabled={page >= totalPages - 1} onClick={() => onPageChange(page + 1)}>
        Next <ChevronRight aria-hidden="true" />
      </Button>
    </nav>
  );
}

export function MeetingStatusBadge({ status }: { status: MeetingStatus }) {
  switch (status) {
    case 'LIVE':
      return (
        <Badge variant="live">
          <span className="size-1.5 animate-pulse rounded-full bg-current" aria-hidden="true" /> Live
        </Badge>
      );
    case 'SCHEDULED':
      return <Badge>Scheduled</Badge>;
    case 'ENDED':
      return <Badge variant="secondary">Ended</Badge>;
    case 'CANCELLED':
      return <Badge variant="destructive">Cancelled</Badge>;
  }
}

/**
 * Confirmation dialog for destructive actions. `onConfirm` may be async; errors are shown inline
 * and the dialog stays open so the user sees the API error.
 */
export function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel = 'Confirm',
  destructive = true,
  withReason = false,
  reasonLabel = 'Reason (optional)',
  onConfirm,
  extraActions,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  title: string;
  description?: ReactNode;
  confirmLabel?: string;
  destructive?: boolean;
  withReason?: boolean;
  reasonLabel?: string;
  onConfirm: (reason?: string) => Promise<unknown> | unknown;
  extraActions?: ReactNode;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reason, setReason] = useState('');
  const reasonId = useId();

  const handle = async () => {
    setBusy(true);
    setError(null);
    try {
      await onConfirm(withReason ? reason.trim() || undefined : undefined);
      setReason('');
      onOpenChange(false);
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <AlertDialog
      open={open}
      onOpenChange={(o) => {
        if (!busy) {
          if (!o) setError(null);
          onOpenChange(o);
        }
      }}
    >
      <AlertDialogContent>
        <div className="grid gap-1.5">
          <AlertDialogTitle>{title}</AlertDialogTitle>
          {description ? (
            <AlertDialogDescription>{description}</AlertDialogDescription>
          ) : (
            <AlertDialogDescription className="sr-only">Confirm this action</AlertDialogDescription>
          )}
        </div>
        {withReason && (
          <div className="grid gap-1.5">
            <Label htmlFor={reasonId}>{reasonLabel}</Label>
            <Textarea id={reasonId} value={reason} maxLength={1000} onChange={(e) => setReason(e.target.value)} />
          </div>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
          <AlertDialogCancel disabled={busy}>Cancel</AlertDialogCancel>
          {extraActions}
          <Button variant={destructive ? 'destructive' : 'default'} loading={busy} onClick={() => void handle()}>
            {confirmLabel}
          </Button>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  );
}
