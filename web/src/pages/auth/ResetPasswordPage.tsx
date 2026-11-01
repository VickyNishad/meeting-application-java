import { useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useForm } from 'react-hook-form';
import { zodResolver } from '@hookform/resolvers/zod';
import { z } from 'zod';
import { CheckCircle2 } from 'lucide-react';
import { AuthCard } from './AuthCard';
import { Button } from '@/components/ui/button';
import { Alert, Input } from '@/components/ui/primitives';
import { FormField, RootError } from '@/components/common';
import { authApi } from '@/lib/api/endpoints';
import { applyApiErrors } from '@/lib/forms';
import { passwordSchema } from '@/lib/validation';

const schema = z
  .object({ newPassword: passwordSchema, confirm: z.string() })
  .refine((v) => v.newPassword === v.confirm, { path: ['confirm'], error: 'Passwords do not match' });

export default function ResetPasswordPage() {
  const [params] = useSearchParams();
  const token = params.get('token');
  const [done, setDone] = useState(false);
  const form = useForm<z.infer<typeof schema>>({ resolver: zodResolver(schema), defaultValues: { newPassword: '', confirm: '' } });

  const onSubmit = form.handleSubmit(async ({ newPassword }) => {
    if (!token) return;
    try {
      await authApi.confirmPasswordReset(token, newPassword);
      setDone(true);
    } catch (e) {
      applyApiErrors(e, form.setError, ['newPassword'], { toast: false });
    }
  });

  return (
    <AuthCard title="Choose a new password">
      {!token ? (
        <Alert variant="destructive">This reset link is missing its token. Request a new link.</Alert>
      ) : done ? (
        <div className="grid gap-4 text-sm" role="status">
          <p className="flex items-center gap-2">
            <CheckCircle2 className="size-5 text-success" aria-hidden="true" /> Password updated. All sessions were signed out.
          </p>
          <Button asChild>
            <Link to="/login">Sign in</Link>
          </Button>
        </div>
      ) : (
        <form onSubmit={onSubmit} className="grid gap-4" noValidate>
          <RootError message={form.formState.errors.root?.server?.message} />
          <FormField label="New password" error={form.formState.errors.newPassword?.message} hint="12–128 characters.">
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('newPassword')} />
            )}
          </FormField>
          <FormField label="Confirm password" error={form.formState.errors.confirm?.message}>
            {({ id, describedBy, invalid }) => (
              <Input id={id} type="password" autoComplete="new-password" aria-describedby={describedBy} aria-invalid={invalid} {...form.register('confirm')} />
            )}
          </FormField>
          <Button type="submit" loading={form.formState.isSubmitting}>
            Update password
          </Button>
        </form>
      )}
      {!done && (
        <p className="mt-4 text-center text-sm">
          <Link to="/forgot-password" className="text-primary hover:underline">
            Request a new link
          </Link>
        </p>
      )}
    </AuthCard>
  );
}
