import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { Loader2, XCircle } from 'lucide-react';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { api } from '@/lib/api/client';
import { usersApi } from '@/lib/api/endpoints';
import { errorMessage } from '@/lib/api/problem';
import { useAuth } from '@/lib/auth/AuthProvider';

/**
 * Landing route after the backend completes OAuth2 login (`/oauth/callback`).
 * The refresh cookie is already set, so POST /auth/refresh yields an access token
 * (the AuthProvider bootstrap performs that call on page load; it is single-flight),
 * then the profile is loaded and the user is sent to the dashboard.
 */
export default function OAuthCallbackPage() {
  const { status, setUser } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState<string | null>(null);
  const [retrying, setRetrying] = useState(false);
  const done = useRef(false);

  useEffect(() => {
    if (status !== 'authenticated' || done.current) return;
    done.current = true;
    usersApi
      .me()
      .then((u) => {
        setUser(u);
        navigate('/dashboard', { replace: true });
      })
      .catch((e: unknown) => setError(errorMessage(e)));
  }, [status, setUser, navigate]);

  const retry = async () => {
    setRetrying(true);
    setError(null);
    const auth = await api.refresh();
    setRetrying(false);
    if (!auth) setError('Your sign-in session could not be established.');
  };

  const failed = status === 'anonymous' || error !== null;

  return (
    <AuthCard title="Signing you in">
      <div className="flex flex-col items-center gap-4 py-4 text-center" aria-live="polite">
        {!failed ? (
          <>
            <Loader2 className="size-8 animate-spin text-primary" aria-hidden="true" />
            <p>Finishing single sign-on…</p>
          </>
        ) : (
          <>
            <XCircle className="size-10 text-destructive" aria-hidden="true" />
            <p role="alert">{error ?? 'Your sign-in session could not be established.'}</p>
            <div className="flex flex-wrap justify-center gap-2">
              <Button onClick={() => void retry()} loading={retrying}>
                Try again
              </Button>
              <Button variant="outline" asChild>
                <Link to="/login?error=oauth">Back to sign in</Link>
              </Button>
            </div>
          </>
        )}
      </div>
    </AuthCard>
  );
}
