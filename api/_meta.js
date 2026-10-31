// Meta Model API helpers (server only). Files starting with "_" are not routes on Vercel.
// Verified against dev.meta.ai docs (Chat Completions, Structured output, Speech to text), Oct 2026.
import { createHash } from 'node:crypto';

export const BASE_URL = (process.env.META_BASE_URL || 'https://api.meta.ai/v1').replace(/\/$/, '');
export const MODEL = process.env.META_MODEL || 'muse-spark-1.3';
// Used only if Meta reports that MODEL is unavailable for this account.
export const FALLBACK_MODEL = process.env.META_FALLBACK_MODEL ?? 'muse-spark-1.1';
export const ASR_MODEL = process.env.META_ASR_MODEL || 'muse-voice-transcribe-1.0';

export class UpstreamError extends Error {
  constructor(status, body) {
    super(`Meta API returned ${status}`);
    this.status = status;
    // Meta uses the OpenAI error envelope: { error: { message, type, code, param } }
    let parsed = null;
    try { parsed = JSON.parse(body); } catch {}
    const err = parsed?.error || parsed || {};
    this.type = err.type || '';
    this.code = err.code || '';
    this.param = err.param || '';
    this.metaMessage = typeof err.message === 'string' ? err.message.slice(0, 300) : String(body || '').slice(0, 300);
  }
}

export function friendlyError(e) {
  if (e instanceof UpstreamError) {
    if (e.status === 401 || e.status === 403) return 'Meta rejected the API key. Check MODEL_API_KEY in Vercel and that this account can use the model.';
    if (e.status === 404) return 'Meta could not find that model. Check META_MODEL in Vercel.';
    if (e.status === 413) return 'The recording was too large for Meta.';
    if (e.status === 429) return 'Meta is busy for this account right now. Wait a moment and try again.';
    if (e.status === 504) return 'Meta took too long to answer. Try again.';
    if (e.status === 400) return 'Meta could not process this request.';
    return `Meta API error (${e.status}). Try again in a moment.`;
  }
  if (e?.name === 'TimeoutError' || e?.name === 'AbortError') return 'Meta took too long to answer. Try again.';
  return 'Something went wrong on the server.';
}

// Logs only status, type, code and parameter. Meta's raw error message is never logged,
// because it can echo user-provided content. Never logs prompts, answers, audio, or keys.
export function logError(where, e) {
  if (e instanceof UpstreamError) {
    console.error(`[${where}] meta status=${e.status} type=${e.type || '-'} code=${e.code || '-'} param=${e.param || '-'}`);
  } else {
    console.error(`[${where}] ${e?.name || 'Error'}`);
  }
}

function textOf(content) {
  if (typeof content === 'string') return content;
  if (Array.isArray(content)) return content.map((p) => (typeof p === 'string' ? p : p?.text || '')).join('');
  return '';
}

const isModelUnavailable = (e) =>
  e instanceof UpstreamError &&
  (e.status === 404 || (e.status === 400 && (/model/i.test(e.code) || e.param === 'model' || /model/i.test(e.metaMessage))));

const isFormatRejected = (e) =>
  e instanceof UpstreamError && e.status === 400 && (e.param === 'response_format' || /response_format|json_schema/i.test(e.metaMessage));

async function post(body, timeoutMs) {
  const r = await fetch(`${BASE_URL}/chat/completions`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      Authorization: `Bearer ${process.env.MODEL_API_KEY}`,
    },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(timeoutMs),
  });
  const raw = await r.text();
  if (!r.ok) throw new UpstreamError(r.status, raw);
  let data;
  try { data = JSON.parse(raw); } catch { throw new Error('Meta returned a response that was not JSON.'); }
  const choice = data?.choices?.[0];
  const text = textOf(choice?.message?.content);
  if (!text) {
    // A reasoning model can spend its whole output budget thinking.
    if (choice?.finish_reason === 'length') throw new Error('The answer was cut off. Try again.');
    throw new Error('Meta returned an empty answer. Try again.');
  }
  return { text, model: data.model || body.model, finishReason: choice?.finish_reason || 'unknown' };
}

/**
 * One Chat Completions call with schema-constrained JSON output.
 * - developer role (Meta's recommended instruction role)
 * - response_format: json_schema (Meta's documented structured output)
 * - max_completion_tokens (max_tokens is the deprecated alias)
 * - reasoning_effort: never "none" (returns 400 on Muse Spark)
 * - temperature left at Meta's recommended default
 */
export async function chat({ instructions, prompt, schema, effort = 'low', maxTokens = 3000, safetyId, deadline }) {
  const build = (model, withSchema) => ({
    model,
    messages: [
      { role: 'developer', content: instructions },
      { role: 'user', content: prompt },
    ],
    ...(withSchema && schema ? { response_format: { type: 'json_schema', json_schema: { name: schema.name, schema: schema.schema } } } : {}),
    reasoning_effort: effort,
    max_completion_tokens: maxTokens,
    ...(safetyId ? { safety_identifier: safetyId } : {}),
  });

  // One overall deadline across retries, so the function always answers before Vercel's 60 s limit.
  deadline = deadline || Date.now() + 52000;
  let model = MODEL;
  let withSchema = true;
  for (let attempt = 0; attempt < 3; attempt++) {
    const left = deadline - Date.now();
    if (left < 3000) throw Object.assign(new Error('deadline'), { name: 'TimeoutError' });
    try {
      const out = await post(build(model, withSchema), left);
      return { ...out, format: withSchema && schema ? 'json_schema' : 'plain' };
    } catch (e) {
      if (isModelUnavailable(e) && FALLBACK_MODEL && model !== FALLBACK_MODEL) {
        logError('chat:model-fallback', e);
        model = FALLBACK_MODEL;
        continue;
      }
      if (isFormatRejected(e) && withSchema) {
        logError('chat:schema-fallback', e);
        withSchema = false;
        continue;
      }
      throw e;
    }
  }
  throw new Error('Meta request failed.');
}

export function parseJson(text) {
  try { return JSON.parse(text); } catch {}
  const cleaned = String(text).replace(/```json|```/g, '');
  const start = cleaned.indexOf('{');
  const end = cleaned.lastIndexOf('}');
  if (start >= 0 && end > start) {
    try { return JSON.parse(cleaned.slice(start, end + 1)); } catch {}
  }
  throw new Error('The model answered in an unexpected format. Try again.');
}

// Meta recommends a stable, hashed end-user identifier for abuse handling.
export function safetyIdFor(ip) {
  const salt = process.env.SAFETY_ID_SALT || 'sada';
  return createHash('sha256').update(`${salt}:${ip}`).digest('hex').slice(0, 32);
}
