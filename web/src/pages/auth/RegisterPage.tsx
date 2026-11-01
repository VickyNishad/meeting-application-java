import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { Controller, useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { useMutation } from '@tanstack/react-query';
import { MailCheck } from 'lucide-react';
import { toast } from 'sonner';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/primitives';
import { FormField, RootError, TimeZoneSelect } from '@/components/common';
import { authApi } from '@/lib/api/endpoints';
import { applyApiErrors, toastError } from '@/lib/forms';
import { browserTimeZone } from '@/lib/time';
import { passwordSchema } from '@/lib/validation';

const schema = z
  .object({
    displayName: z.string().trim().min(1, 'Name is required').max(100),
    email: z.string().trim().pipe(z.email('Enter a valid email address')),
    password: passwordSchema,
    confirm: z.string(),
    timeZone: z.string().min(1),
  })
  .refine((v) => v.password === v.confirm, { path: ['confirm'], error: 'Passwords do not match' });
type Values = z.infer<typeof schema>;

export default function RegisterPage() {
  const [params] = useSearchParams();
  const [registeredEmail, setRegisteredEmail] = useState<string | null>(null);
  const [needsVerification, setNeedsVerification] = useState(true);

  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { displayName: '', email: '', password: '', confirm: '', timeZone: browserTimeZone() },
  });
  const resend = useMutation({
    mutationFn: (email: string) => authApi.resendVerification(email),
    onSuccess: () => toast.success('Verification email re-sent.'),
    onError: (e) => toastError(e),
  });

  const onSubmit = form.handleSubmit(async ({ confirm: _confirm, ...values }) => {
    try {
      const res = await authApi.register(values);
      setNeedsVerification(res.emailVerificationRequired);
      setRegisteredEmail(values.email);
    } catch (e) {
      applyApiErrors(e, form.setError, ['displayName', 'email', 'password', 'timeZone'], { toast: false });
    }
  });

  const loginHref = `/login${params.get('next') ? `?next=${encodeURIComponent(params.get('next') ?? '')}` : ''}`;

  if (registeredEmail) {
    return (
      <AuthCard title={needsVerification ? 'Check your inbox' : 'Account created'}>
        <div className="grid gap-4 text-sm">
          <div className="flex items-start gap-3">
            <MailCheck className="mt-0.5 size-5 text-primary" aria-hidden="true" />
            <p>
              {needsVerification ? (
                <>
                  We sent a verification link to <strong>{registeredEmail}</strong>. Open it to activate your account.
                </>
              ) : (
                'Your account is ready. You can sign in now.'
              )}
            </p>
          </div>
          {needsVerification && (
            <Button variant="outline" loading={resend.isPending} onClick={() => resend.mutate(registeredEmail)}>
              Resend email
            </Button>
          )}
          <Button asChild>
            <Link to={loginHref}>Go to sign in</Link>
          </Button>
        </div>
      </AuthCard>
    );
  }

  const errors = form.formState.errors;
  return (
    <AuthCard
      title="Create your account"
      description="Host meetings and share booking pages in minutes."
      footer={
        <>
          Already have an account?{' '}
          <Link to={loginHref} className="font-medium text-primary hover:underline">
            Sign in
          </Link>
        </>
      }
    >
      <form onSubmit={onSubmit} className="grid gap-4" noValidate>
        <RootError message={errors.root?.server?.message} />
        <FormField label="Full name" error={errors.displayName?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} autoComplete="name" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('displayName')} />
          )}
        </FormField>
        <FormField label="Email" error={errors.email?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="email" autoComplete="email" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('email')} />
          )}
        </FormField>
        <FormField label="Password" error={errors.password?.message} hint="12–128 characters.">
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('password')} />
          )}
        </FormField>
        <FormField label="Confirm password" error={errors.confirm?.message}>
          {({ id, describedBy, invalid }) => (
            <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('confirm')} />
          )}
        </FormField>
        <FormField label="Time zone" error={errors.timeZone?.message}>
          {({ id, describedBy, invalid }) => (
            <Controller
              control={form.control}
              name="timeZone"
              render={({ field }) => (
                <TimeZoneSelect id={id} value={field.value} onChange={field.onChange} aria-describedby={describedBy} aria-invalid={invalid} />
              )}
            />
          )}
        </FormField>
        <Button type="submit" loading={form.formState.isSubmitting} className="w-full">
          Create account
        </Button>
      </form>
    </AuthCard>
  );
}
