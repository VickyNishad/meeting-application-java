import type { FieldValues, Path, UseFormSetError } from 'react-hook-form';
import { toast } from 'sonner';
import { ApiError, errorMessage } from './api/problem';

/**
 * Maps Problem Details `errors[]` onto react-hook-form fields. Fields that don't exist in the form
 * (or a missing field name) are reported via the root error. Always toasts the `detail`.
 * Returns the list of field names that were mapped.
 */
export function applyApiErrors<T extends FieldValues>(
  err: unknown,
  setError: UseFormSetError<T>,
  knownFields: readonly string[],
  opts: { toast?: boolean; aliases?: Record<string, string> } = {},
): string[] {
  const mapped: string[] = [];
  if (err instanceof ApiError) {
    const unmapped: string[] = [];
    for (const fe of err.fieldErrors) {
      // Accept nested paths like "inviteeEmails[0]" by matching the root field.
      const apiRoot = fe.field.split(/[.[]/)[0] ?? '';
      const root = opts.aliases?.[apiRoot] ?? apiRoot;
      if (root && knownFields.includes(root)) {
        setError(root as Path<T>, { type: 'server', message: fe.message });
        mapped.push(root);
      } else if (fe.message) {
        unmapped.push(fe.field ? `${fe.field}: ${fe.message}` : fe.message);
      }
    }
    const rootMsg = [err.detail, ...unmapped].filter(Boolean).join(' — ');
    setError('root.server' as Path<T>, { type: 'server', message: rootMsg });
  } else {
    setError('root.server' as Path<T>, { type: 'server', message: errorMessage(err) });
  }
  if (opts.toast !== false) toast.error(errorMessage(err));
  return mapped;
}

export function toastError(err: unknown, fallbackTitle?: string): void {
  const msg = errorMessage(err);
  toast.error(fallbackTitle ? `${fallbackTitle}: ${msg}` : msg);
}
