import { z } from 'zod';

/** Password rule from the API contract: 12..128 characters. */
export const passwordSchema = z
  .string()
  .min(12, 'Use at least 12 characters')
  .max(128, 'Use at most 128 characters');

export const emailListSchema = z
  .string()
  .transform((s) =>
    s
      .split(/[\s,;]+/)
      .map((e) => e.trim())
      .filter(Boolean),
  )
  .pipe(z.array(z.email('Contains an invalid email address')).max(300, 'At most 300 emails'));

/**
 * A number field (RHF `valueAsNumber`) whose type error — empty input → NaN — reads `message`.
 * zod 4 replaced `invalid_type_error`; checks such as `.int()`/`.min()` keep their own messages.
 */
export const numberField = (message: string) =>
  z.number({ error: (iss) => (iss.code === 'invalid_type' && iss.expected === 'number' ? message : undefined) });
