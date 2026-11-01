import { useState } from 'react';
import { Link } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { MailCheck } from 'lucide-react';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/primitives';
import { FormField, RootError } from '@/components/common';
import { authApi } from '@/lib/api/endpoints';
import { applyApiErrors } from '@/lib/forms';

const schema = z.object({ email: z.string().trim().pipe(z.email('Enter a valid email address')) });

export default function ForgotPasswordPage() {
  const [sentTo, setSentTo] = useState<string | null>(null);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { email: '' } });

  const onSubmit = form.handleSubmit(async ({ email }) => {
    try {
      await authApi.requestPasswordReset(email);
      setSentTo(email);
    } catch (e) {
      applyApiErrors(e, form.setError, ['email'], { toast: false });
    }
  });

  return (
    <AuthCard
      title="Reset your password"
      description="We'll email you a link to choose a new password."
      footer={
        <Link to="/login" className="text-primary hover:underline">
          Back to sign in
        </Link>
      }
    >
      {sentTo ? (
        <div className="flex items-start gap-3 text-sm" role="status">
          <MailCheck className="mt-0.5 size-5 text-primary" aria-hidden="true" />
          <p>
            If an account exists for <strong>{sentTo}</strong>, a reset link has been sent. The link expires soon, so use
            it promptly.
          </p>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="grid gap-4" noValidate>
          <RootError message={form.formState.errors.root?.server?.message} />
          <FormField label="Email" error={form.formState.errors.email?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="email" autoComplete="email" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('email')} />
            )}
          </FormField>
          <Button type="submit" loading={form.formState.isSubmitting}>
            Send reset link
          </Button>
        </form>
      )}
    </AuthCard>
  );
}
