import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router';
import { Loader2 } from 'lucide-react';
import { useAuth } from './AuthProvider';

export function FullPageSpinner({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex min-h-[50vh] items-center justify-center" role="status" aria-live="polite">
      <Loader2 className="size-6 animate-spin text-muted-foreground" aria-hidden="true" />
      <span className="sr-only">{label}</span>
    </div>
  );
}

export function loginRedirectPath(pathname: string, search: string): string {
  const next = `${pathname}${search}`;
  return next && next !== '/' ? `/login?next=${encodeURIComponent(next)}` : '/login';
}

/** Renders children only for signed-in users; otherwise redirects to /login?next=… */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner label="Checking your session…" />;
  if (status !== 'authenticated') {
    return <Navigate to={loginRedirectPath(location.pathname, location.search)} replace />;
  }
  return <>{children}</>;
}

/** Restricts a route to SYSTEM_ADMIN users (backend enforces it too). */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const { status, user } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner />;
  if (status !== 'authenticated') {
    return <Navigate to={loginRedirectPath(location.pathname, location.search)} replace />;
  }
  if (user?.systemRole !== 'SYSTEM_ADMIN') {
    return (
      <div className="mx-auto max-w-md py-24 text-center">
        <h1 className="text-xl font-semibold">Access denied</h1>
        <p className="mt-2 text-muted-foreground">The administration area is only available to system administrators.</p>
      </div>
    );
  }
  return <>{children}</>;
}

/** For login/register: signed-in users are sent to their destination. */
export function PublicOnly({ children }: { children: ReactNode }) {
  const { status } = useAuth();
  const location = useLocation();
  if (status === 'loading') return <FullPageSpinner />;
  if (status === 'authenticated') {
    const next = new URLSearchParams(location.search).get('next');
    return <Navigate to={safeNext(next)} replace />;
  }
  return <>{children}</>;
}

/**
 * Only allow same-origin relative redirects. Browsers treat `\` like `/` and drop tabs/newlines, so
 * `/\evil.com` or `/\t/evil.com` would become a protocol-relative URL: reject backslashes and control
 * characters outright, then confirm with the URL parser that the target stays on this origin.
 */
export function safeNext(next: string | null | undefined): string {
  const fallback = '/dashboard';
  // eslint-disable-next-line no-control-regex
  if (!next || !next.startsWith('/') || next.startsWith('//') || /[\\\u0000-\u001f\u007f]/.test(next)) return fallback;
  try {
    const base = new URL(window.location.origin);
    const url = new URL(next, base);
    if (url.origin !== base.origin) return fallback;
    return `${url.pathname}${url.search}${url.hash}`;
  } catch {
    return fallback;
  }
}
