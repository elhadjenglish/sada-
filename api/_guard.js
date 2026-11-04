// Request guard for the public API routes: method, origin, content type, body size, and rate limits.
// This is abuse protection, not authentication: anyone can still use the app in a browser,
// but scripts and other sites can't drain the Meta budget.

const memory = new Map(); // fallback counters, per serverless instance

function clientIp(req) {
  const h = req.headers || {};
  const raw = h['x-vercel-forwarded-for'] || h['x-real-ip'] || h['x-forwarded-for'] || req.socket?.remoteAddress || 'unknown';
  return String(Array.isArray(raw) ? raw[0] : raw).split(',')[0].trim() || 'unknown';
}

function allowedOrigins(req) {
  const list = (process.env.ALLOWED_ORIGINS || '').split(',').map((s) => s.trim()).filter(Boolean);
  const host = req.headers?.host;
  if (host) list.push(`https://${host}`);
  if (process.env.VERCEL_URL) list.push(`https://${process.env.VERCEL_URL}`);
  if (process.env.VERCEL_PROJECT_PRODUCTION_URL) list.push(`https://${process.env.VERCEL_PROJECT_PRODUCTION_URL}`);
  if (process.env.NODE_ENV !== 'production' || process.env.VERCEL_ENV === 'development') {
    list.push('http://localhost:3000', 'http://localhost:5173', host ? `http://${host}` : '');
  }
  return new Set(list.filter(Boolean));
}

const redisUrl = () => process.env.UPSTASH_REDIS_REST_URL || process.env.KV_REST_API_URL;
const redisToken = () => process.env.UPSTASH_REDIS_REST_TOKEN || process.env.KV_REST_API_TOKEN;

// Fixed-window counter. Uses Upstash Redis (shared across instances) when configured,
// otherwise an in-memory map (per instance: weaker, but still blunts bursts).
async function hit(key, windowSec) {
  const bucket = Math.floor(Date.now() / 1000 / windowSec);
  const k = `sada:rl:${key}:${bucket}`;
  if (redisUrl() && redisToken()) {
    try {
      const r = await fetch(`${redisUrl()}/pipeline`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${redisToken()}`, 'Content-Type': 'application/json' },
        body: JSON.stringify([['INCR', k], ['EXPIRE', k, String(windowSec + 5)]]),
        signal: AbortSignal.timeout(1500),
      });
      if (r.ok) {
        const out = await r.json();
        const n = Number(out?.[0]?.result);
        if (Number.isFinite(n)) return n;
      }
    } catch {}
  }
  const now = Date.now();
  const entry = memory.get(k);
  if (!entry || entry.exp < now) {
    memory.set(k, { n: 1, exp: now + windowSec * 1000 });
    if (memory.size > 5000) for (const [mk, v] of memory) if (v.exp < now) memory.delete(mk);
    return 1;
  }
  entry.n += 1;
  return entry.n;
}

export const sharedLimiter = () => !!(redisUrl() && redisToken());

/**
 * Runs all checks. Returns { ip } when the request may proceed; otherwise sends the error and returns null.
 * limits: [{ name, max, windowSec, scope: 'ip' | 'global' }]
 */
export async function guard(req, res, { maxBodyBytes, limits }) {
  res.setHeader('Cache-Control', 'no-store');
  res.setHeader('X-Content-Type-Options', 'nosniff');

  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    res.status(405).json({ error: 'Use POST.' });
    return null;
  }

  // Browsers always send Origin on cross-site POSTs; same-origin fetches send it too in modern browsers.
  const origin = req.headers?.origin;
  if (origin && !allowedOrigins(req).has(origin)) {
    res.status(403).json({ error: 'This API only serves the Sada app.' });
    return null;
  }
  // The Android app is not a browser and sends no Origin; it identifies itself with this header instead. Neither
  // header is a secret (both can be sent by any script); the per-IP and global daily limits below protect the cost.
  const appClient = String(req.headers?.['x-sada-client'] || '').toLowerCase() === 'android';
  if (!origin && !appClient && process.env.REQUIRE_ORIGIN !== 'false' && process.env.VERCEL_ENV === 'production') {
    res.status(403).json({ error: 'This API only serves the Sada app.' });
    return null;
  }

  const ct = String(req.headers?.['content-type'] || '');
  if (!ct.toLowerCase().startsWith('application/json')) {
    res.status(415).json({ error: 'Send JSON.' });
    return null;
  }
  const len = Number(req.headers?.['content-length'] || 0);
  const size = len || Buffer.byteLength(typeof req.body === 'string' ? req.body : JSON.stringify(req.body || {}));
  if (size > maxBodyBytes) {
    res.status(413).json({ error: 'Request too large.' });
    return null;
  }

  if (!process.env.MODEL_API_KEY) {
    res.status(500).json({ error: 'The server has no MODEL_API_KEY yet. Add it in Vercel → Settings → Environment Variables, then redeploy.' });
    return null;
  }

  const ip = clientIp(req);
  for (const l of limits) {
    const n = await hit(`${l.name}:${l.scope === 'global' ? 'all' : ip}`, l.windowSec);
    if (n > l.max) {
      res.setHeader('Retry-After', String(l.windowSec));
      res.status(429).json({
        error: l.scope === 'global'
          ? 'Sada has reached its daily AI limit. Try again tomorrow.'
          : `Too many requests. Wait ${l.windowSec >= 3600 ? 'a while' : l.windowSec + ' seconds'} and try again.`,
      });
      return null;
    }
  }
  return { ip };
}

export function readBody(req) {
  const b = req.body;
  if (!b) return {};
  if (typeof b === 'string') {
    try { return JSON.parse(b); } catch { return null; }
  }
  return typeof b === 'object' ? b : null;
}

const envInt = (name, d) => {
  const n = parseInt(process.env[name] || '', 10);
  return Number.isFinite(n) && n > 0 ? n : d;
};
export const LIMITS = {
  // Per-IP limits sized for one learner using the app normally, with headroom.
  aiMinute: () => envInt('RL_AI_PER_MIN', 20),
  aiDay: () => envInt('RL_AI_PER_DAY', 400),
  exercisesHour: () => envInt('RL_EXERCISES_PER_HOUR', 20),
  asrMinute: () => envInt('RL_ASR_PER_MIN', 12),
  asrDay: () => envInt('RL_ASR_PER_DAY', 300),
  pingMinute: () => envInt('RL_PING_PER_MIN', 3),
  // Whole-app ceiling per day to cap spend if limits are evaded.
  globalDay: () => envInt('GLOBAL_DAILY_CAP', 5000),
};
