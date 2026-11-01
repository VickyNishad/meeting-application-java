// Shared helpers for QA suites (Node 20+, no dependencies).
import crypto from 'node:crypto';

export const BASE = process.env.QA_BASE_URL || 'http://127.0.0.1:8081/api/v1';
export const JWT_SECRET = process.env.QA_JWT_SECRET || 'localdev-jwt-secret-0123456789-abcdefghijklmnopqrstuvwxyz';
export const JWT_ISSUER = process.env.QA_JWT_ISSUER || 'meet-platform';
export const PASSWORD = 'a long local test passphrase';

export const rid = () => crypto.randomBytes(4).toString('hex');
export const iso = (ms) => new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z');
export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export async function call(method, path, { body, token, headers = {} } = {}) {
  const h = { ...headers };
  if (token) h.Authorization = `Bearer ${token}`;
  let payload;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) {
    h['Content-Type'] ??= 'application/json';
    payload = typeof body === 'string' ? body : JSON.stringify(body);
  }
  const started = performance.now();
  let res;
  try {
    res = await fetch(BASE + path, { method, headers: h, body: payload, redirect: 'manual' });
  } catch (e) {
    return { status: 0, json: null, text: String(e), ms: performance.now() - started, headers: new Headers() };
  }
  const text = await res.text();
  let json = null;
  try { json = text ? JSON.parse(text) : null; } catch { /* not json */ }
  return { status: res.status, json, text, ms: performance.now() - started, headers: res.headers };
}

export async function newUser(name = 'QA User') {
  const email = `${name.toLowerCase().replace(/[^a-z]/g, '')}+${rid()}@example.test`;
  const reg = await call('POST', '/auth/register', { body: { email, password: PASSWORD, displayName: name, timeZone: 'UTC' } });
  if (reg.status !== 201) throw new Error(`register ${reg.status} ${reg.text}`);
  const login = await call('POST', '/auth/login', { body: { email, password: PASSWORD, clientType: 'NATIVE' } });
  if (login.status !== 200) throw new Error(`login ${login.status} ${login.text}`);
  return { email, id: reg.json.userId, token: login.json.accessToken, refresh: login.json.refreshToken };
}

/** HS256 JWT signed with an arbitrary secret, for tampering tests. */
export function jwt(payload, { secret = JWT_SECRET, alg = 'HS256' } = {}) {
  const b64 = (o) => Buffer.from(JSON.stringify(o)).toString('base64url');
  const head = b64({ alg, typ: 'JWT' });
  const body = b64(payload);
  if (alg === 'none') return `${head}.${body}.`;
  const sig = crypto.createHmac('sha256', secret).update(`${head}.${body}`).digest('base64url');
  return `${head}.${body}.${sig}`;
}

export function decode(token) {
  return JSON.parse(Buffer.from(token.split('.')[1], 'base64url').toString());
}

/** Runs async tasks with bounded concurrency. */
export async function pool(items, concurrency, fn) {
  const results = new Array(items.length);
  let next = 0;
  async function worker() {
    while (next < items.length) {
      const i = next++;
      results[i] = await fn(items[i], i);
    }
  }
  await Promise.all(Array.from({ length: Math.min(concurrency, items.length) }, worker));
  return results;
}

export function percentiles(values) {
  const s = [...values].sort((a, b) => a - b);
  const p = (q) => (s.length ? s[Math.min(s.length - 1, Math.ceil(q * s.length) - 1)] : NaN);
  return { n: s.length, p50: p(0.5), p95: p(0.95), p99: p(0.99), max: s[s.length - 1] };
}

export class Report {
  constructor(title) { this.title = title; this.rows = []; this.section = ''; }
  check(name, ok, detail = '') { this.rows.push({ section: this.section, name, ok: !!ok, detail: ok ? '' : detail }); return ok; }
  status(name, r, status, code) {
    const statuses = Array.isArray(status) ? status : [status];
    return this.check(name, statuses.includes(r.status) && (!code || r.json?.code === code),
      `expected ${statuses.join('|')}${code ? ' ' + code : ''}, got ${r.status} ${r.json?.code ?? ''} ${r.text.slice(0, 240)}`);
  }
  print() {
    console.log(`\n=== ${this.title} ===`);
    for (const r of this.rows) console.log(`${r.ok ? 'PASS' : 'FAIL'}  [${r.section}] ${r.name}${r.ok ? '' : '\n        ' + r.detail}`);
    const failed = this.rows.filter((r) => !r.ok).length;
    console.log(`\n${this.rows.length - failed}/${this.rows.length} passed, ${failed} failed`);
    return failed;
  }
}
