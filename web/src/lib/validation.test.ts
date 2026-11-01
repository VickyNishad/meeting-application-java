import { describe, expect, it } from 'vitest';
import { emailListSchema, numberField, passwordSchema } from './validation';

const firstMessage = (r: { success: boolean; error?: { issues: { message: string }[] } }) => r.error?.issues[0]?.message;

describe('validation schemas (zod 4)', () => {
  it('numberField: empty input (NaN) reads the custom message, checks keep their own', () => {
    const s = numberField('Enter a duration').int().min(5, 'At least 5 minutes');
    expect(firstMessage(s.safeParse(Number.NaN))).toBe('Enter a duration');
    expect(firstMessage(s.safeParse(undefined))).toBe('Enter a duration');
    expect(firstMessage(s.safeParse(3))).toBe('At least 5 minutes');
    expect(firstMessage(s.safeParse(7.5))).not.toBe('Enter a duration');
    expect(s.safeParse(30).success).toBe(true);
  });

  it('emailListSchema splits, trims and validates with the custom messages', () => {
    expect(emailListSchema.parse(' a@b.co, c@d.org;\ne@f.io ')).toEqual(['a@b.co', 'c@d.org', 'e@f.io']);
    expect(emailListSchema.parse('')).toEqual([]);
    expect(firstMessage(emailListSchema.safeParse('a@b.co nope'))).toBe('Contains an invalid email address');
    const many = Array.from({ length: 301 }, (_, i) => `u${i}@x.io`).join(',');
    expect(firstMessage(emailListSchema.safeParse(many))).toBe('At most 300 emails');
  });

  it('passwordSchema keeps its messages', () => {
    expect(firstMessage(passwordSchema.safeParse('short'))).toBe('Use at least 12 characters');
    expect(firstMessage(passwordSchema.safeParse('x'.repeat(129)))).toBe('Use at most 128 characters');
  });
});
