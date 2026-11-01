import { useEffect, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { CheckCircle2, Loader2, XCircle } from 'lucide-react';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { authApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';

type State = { kind: 'pending' } | { kind: 'ok' } | { kind: 'error'; message: string };

export default function VerifyEmailPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [state, setState] = useState<State>(() =>
    token ? { kind: 'pending' } : { kind: 'error', message: 'The verification link is missing its token.' },
  );
  const started = useRef<string | null>(null);

  useEffect(() => {
    // Guard against StrictMode double-invocation: verification tokens are single-use.
    if (!token || started.current === token) return;
    started.current = token;
    authApi
      .verifyEmail(token)
      .then(() => setState({ kind: 'ok' }))
      .catch((e: unknown) => setState({ kind: 'error', message: errorMessage(e) }));
  }, [token]);

  return (
    <AuthCard title="Email verification">
      <div className="flex flex-col items-center gap-4 py-4 text-center" aria-live="polite">
        {state.kind === 'pending' && (
          <>
            <Loader2 className="size-8 animate-spin text-primary" aria-hidden="true" />
            <p>Verifying your email…</p>
          </>
        )}
        {state.kind === 'ok' && (
          <>
            <CheckCircle2 className="size-10 text-success" aria-hidden="true" />
            <p>Your email is verified. You can now sign in.</p>
            <Button asChild>
              <Link to="/login">Sign in</Link>
            </Button>
          </>
        )}
        {state.kind === 'error' && (
          <>
            <XCircle className="size-10 text-destructive" aria-hidden="true" />
            <p role="alert">{state.message}</p>
            <p className="text-sm text-muted-foreground">You can request a new link from the sign-in page.</p>
            <Button asChild variant="outline">
              <Link to="/login">Back to sign in</Link>
            </Button>
          </>
        )}
      </div>
    </AuthCard>
  );
}
