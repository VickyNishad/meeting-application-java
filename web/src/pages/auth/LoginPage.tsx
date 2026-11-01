import { useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation, useQuery } from '@tanstack/react-query';
import { AlertTriangle, MailWarning } from 'lucide-react';
import { toast } from 'sonner';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { Alert, Input, Separator } from '@/components/ui/primitives';
import { FormField, RootError } from '@/components/common';
import { authApi } from '@/lib/api/endpoints';
import { ApiError } from '@/lib/api/problem';
import { applyApiErrors, toastError } from '@/lib/forms';
import { useAuth } from '@/lib/auth/AuthProvider';
import { safeNext } from '@/lib/auth/guards';

const PROVIDER_LABEL: Record<string, string> = { google: 'Google', microsoft: 'Microsoft' };

const OAUTH_ERRORS: Record<string, string> = {
  oauth: 'Single sign-on did not complete. Please try again or sign in with your email and password.',
  oauth_account:
    'We could not sign you in with that account. It may be disabled, or the email is already registered with a password — sign in with your password instead.',
  oauth_profile: 'Your identity provider did not share the profile details we need (such as a verified email address).',
};

const schema = z.object({
  email: z.string().trim().pipe(z.email('Enter a valid email address')),
  password: z.string().min(1, 'Password is required'),
});
type Values = z.infer<typeof schema>;

export default function LoginPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const { signIn } = useAuth();
  const errorParam = params.get('error');
  const oauthError = errorParam ? (OAUTH_ERRORS[errorParam] ?? OAUTH_ERRORS.oauth) : null;
  const [unverifiedEmail, setUnverifiedEmail] = useState<string | null>(null);

  const form = useForm<Values>({ resolver: zodResolver(schema), defaultValues: { email: '', password: '' } });
  const providers = useQuery({ queryKey: ['oauth-providers'], queryFn: authApi.oauthProviders, staleTime: 5 * 60_000 });

  const resend = useMutation({
    mutationFn: (email: string) => authApi.resendVerification(email),
    onSuccess: () => toast.success('If the account exists, a new verification email is on its way.'),
    onError: (e) => toastError(e),
  });

  const onSubmit = form.handleSubmit(async (values) => {
    setUnverifiedEmail(null);
    try {
      const auth = await authApi.login(values);
      signIn(auth);
      navigate(safeNext(params.get('next')), { replace: true });
    } catch (e) {
      if (e instanceof ApiError && e.code === 'EMAIL_NOT_VERIFIED') setUnverifiedEmail(values.email);
      applyApiErrors(e, form.setError, ['email', 'password'], { toast: false });
    }
  });

  const errors = form.formState.errors;

  return (
    <AuthCard
      title="Welcome back"
      description="Sign in to host and join meetings."
      footer={
        <>
          New here?{' '}
          <Link to={`/register${params.get('next') ? `?next=${encodeURIComponent(params.get('next') ?? '')}` : ''}`} className="font-medium text-primary hover:underline">
            Create an account
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="grid gap-4" noValidate>
        {oauthError && (
          <Alert variant="destructive">
            <AlertTriangle aria-hidden="true" />
            <span>{oauthError}</span>
          </Alert>
        )}
        <RootError message={errors.root?.server?.message} />
        {unverifiedEmail && (
          <Alert variant="warning">
            <MailWarning aria-hidden="true" />
            <div className="grid gap-2">
              <span>Your email address hasn't been verified yet.</span>
              <Button type="button" size="sm" variant="outline" loading={resend.isPending} onClick={() => resend.mutate(unverifiedEmail)}>
                Resend verification email
              </Button>
            </div>
          </Alert>
        )}
        <FormField label="Email" error={errors.email?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" autoComplete="email" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('email')} />
          )}
        </FormField>
        <FormField label="Password" error={errors.password?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="password" autoComplete="current-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('password')} />
          )}
        </FormField>
        <div className="-mt-2 text-right text-sm">
          <Link to="/forgot-password" className="text-primary hover:underline">
            Forgot password?
          </Link>
        </div>
        <Button type="submit" loading={form.formState.isSubmitting} className="w-full">
          Sign in
        </Button>
      </form>
      {providers.data && providers.data.length > 0 && (
        <>
          <div className="my-5 flex items-center gap-3 text-xs text-muted-foreground">
            <Separator className="flex-1" /> or continue with <Separator className="flex-1" />
          </div>
          <div className="grid gap-2">
            {providers.data.map((p) => (
              <Button key={p.id} variant="outline" asChild>
                {/* Full-page navigation: the authorization URL is served by the backend (proxied). */}
                <a href={p.authorizationUrl}>Continue with {PROVIDER_LABEL[p.id] ?? p.id}</a>
              </Button>
            ))}
          </div>
        </>
      )}
    </AuthCard>
  );
}
