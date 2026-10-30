// Browser side of the AI layer. All Meta calls go through our own /api routes, so the key never reaches the device.
// Every request has a deadline: a request that hangs ends in a clear error instead of an endless spinner.

const TIMEOUTS = { explain: 45000, grade: 45000, exercises: 60000, ping: 30000, transcribe: 35000 };

async function post(path, body, { timeoutMs, signal } = {}) {
  const ctrl = new AbortController();
  const timer = setTimeout(() => ctrl.abort(new DOMException('timeout', 'TimeoutError')), timeoutMs);
  const onOuterAbort = () => ctrl.abort(signal.reason);
  signal?.addEventListener('abort', onOuterAbort);
  let r;
  try {
    r = await fetch(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body), signal: ctrl.signal });
    let data = null;
    try { data = await r.json(); } catch {}
    if (!r.ok) {
      if (r.status === 404 && !data?.error) throw new Error('The AI server isn’t running here. Open the Vercel deployment so /api routes exist.');
      throw new Error(data?.error || `Request failed (${r.status}).`);
    }
    if (!data || typeof data !== 'object') throw new Error('The server sent an empty answer. Try again.');
    return data;
  } catch (e) {
    if (ctrl.signal.aborted && !signal?.aborted) throw new Error(`No answer after ${Math.round(timeoutMs / 1000)} seconds. Check your connection and try again.`);
    if (e.name === 'AbortError') throw e;
    if (e instanceof TypeError) throw new Error('No connection. Check your internet and try again.');
    throw e;
  } finally {
    clearTimeout(timer);
    signal?.removeEventListener('abort', onOuterAbort);
  }
}

// `source` is 'meta' for real Meta answers and 'demo' for preview sample data. The UI labels anything that isn't Meta.
export async function askAI(task, payload, { signal } = {}) {
  const data = await post('/api/ai', { task, payload }, { timeoutMs: TIMEOUTS[task] || 45000, signal });
  if (!data.result || typeof data.result !== 'object') throw new Error('The answer was incomplete. Try again.');
  return { ...data.result, _source: data.source || 'unknown' };
}

export async function pingAI() {
  return post('/api/ai', { task: 'ping', payload: {} }, { timeoutMs: TIMEOUTS.ping });
}

export async function transcribe(base64Wav, { keywords, languages } = {}) {
  const data = await post('/api/transcribe', { audio: base64Wav, keywords, languages }, { timeoutMs: TIMEOUTS.transcribe });
  if (typeof data.text !== 'string') throw new Error('No transcript came back. Try again.');
  return { text: data.text, source: data.source || 'unknown' };
}
