// Run against a production bundle served at http://localhost:8765 with tone.wav. Requires Playwright. SIMULATED recognizer and voice.
// SIMULATED browser test: real Sada UI, real <audio> playback and real /api request path,
// with the speech recognizer and speech synthesis replaced by controllable fakes.
import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
const results = [];
const check = (name, ok, detail = '') => { results.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  — ' + detail : ''}`); };

const init = () => {
  window.__mlog = [];
  const P = HTMLMediaElement.prototype;
  const desc = Object.getOwnPropertyDescriptor(P, 'currentTime');
  Object.defineProperty(P, 'currentTime', { get() { return desc.get.call(this); }, set(v) { window.__mlog.push(`seek ${v.toFixed?.(3)} (was ${desc.get.call(this).toFixed(3)})`); desc.set.call(this, v); }, configurable: true });
  for (const m of ['pause', 'play']) { const orig = P[m]; P[m] = function () { window.__mlog.push(`${m} @${desc.get.call(this).toFixed(3)}`); return orig.call(this); }; }
  localStorage.setItem('sada:v1', JSON.stringify({
    version: 1, profile: { name: 'Hadj', level: 'B1', explainLang: 'both', done: true },
    settings: { wakePhrase: 'Hey Sada', resumePhrase: 'Continue', autoSave: true, rate: 1 },
    words: [], progress: {}, activity: {}, reviewSkippedOn: null,
    customEpisodes: [{ id: 'mine-audio', mine: true, title: 'Tone test episode', level: 'B1', category: 'Mine', minutes: 1,
      audioUrl: 'http://localhost:8765/tone.wav', text: 'She explained that she was reliable. When she promised to do something, she did it. The manager listened carefully and took notes.' }],
  }));
  window.__sr = { instances: [], get current() { return this.instances.filter((r) => r.running).at(-1); } };
  class FakeSR {
    constructor() { this.running = false; window.__sr.instances.push(this); }
    start() { this.running = true; }
    abort() { if (!this.running) return; this.running = false; setTimeout(() => this.onend?.(), 5); }
    stop() { this.abort(); }
  }
  window.webkitSpeechRecognition = window.SpeechRecognition = FakeSR;
  window.__sr.emit = (text, isFinal) => { const r = window.__sr.current; if (!r) return false; r.onresult({ resultIndex: 0, results: [Object.assign([{ transcript: text }], { isFinal })] }); return true; };
  window.__sr.fail = (code) => { const r = window.__sr.current; r.onerror({ error: code }); r.running = false; r.onend?.(); };
  window.__tts = { spoken: [], speakingWhileListening: false, delay: 200 };
  class FakeUtterance { constructor(text) { this.text = text; } }
  window.SpeechSynthesisUtterance = FakeUtterance;
  const voices = [{ lang: 'en-US', name: 'Fake EN' }, { lang: 'ar-SA', name: 'Fake AR' }];
  const synth = {
    speak(u) {
      window.__tts.spoken.push({ text: u.text, lang: u.lang });
      if (window.__sr.current) window.__tts.speakingWhileListening = true;
      setTimeout(() => { u.onstart?.(); setTimeout(() => u.onend?.(), window.__tts.delay); }, 10);
    },
    cancel() {}, getVoices: () => voices, pause() {}, resume() {}, speaking: false,
  };
  Object.defineProperty(window, 'speechSynthesis', { value: synth, configurable: true });
};

const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required'] });
const ctx = await b.newContext({ viewport: { width: 393, height: 851 } });
await ctx.addInitScript(init);
const page = await ctx.newPage();
const errs = []; page.on('pageerror', (e) => errs.push(e.message));
const aiRequests = [];
let aiMode = 'ok';
await page.route('**/api/ai', async (route) => {
  const body = JSON.parse(route.request().postData());
  aiRequests.push(body);
  if (aiMode === 'fail') return route.fulfill({ status: 502, contentType: 'application/json', body: JSON.stringify({ error: 'Meta API error (500).' }) });
  await new Promise((r) => setTimeout(r, 300));
  route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ source: 'meta', model: 'muse-spark-1.3', result: {
    is_vocab: true, word: 'reliable', lemma: 'reliable', pos: 'adjective', ipa: 'rɪˈlaɪəbl', meaning_en: 'you can trust them', meaning_ar: 'يُعتمد عليه',
    in_context_en: 'Here it means Nadia keeps her promises.', in_context_ar: 'تعني أن نادية تفي بوعودها.', example: 'My car is reliable.', answer_en: '', answer_ar: '' } }) });
});

await page.goto('http://localhost:8765/index.html');
await page.click('.tab:has-text("Listen")');
await page.click('text=Tone test episode');
await page.click('.play');
await page.waitForTimeout(1500);
const audioT = () => page.evaluate(() => { const a = document.querySelector('audio'); return { t: a.currentTime, paused: a.paused }; });
check('Episode audio is playing', !(await audioT()).paused);

await page.click('[aria-label="Turn hands-free on"]');
await page.waitForTimeout(200);
check('Hands-free starts the recognizer', await page.evaluate(() => !!window.__sr.current));
check('Status shows the wake phrase', /Say “Hey Sada”/.test(await page.locator('.hint.live').innerText()));

await page.evaluate(() => window.__sr.emit('she explained that she was reliable', true));
await page.waitForTimeout(200);
check('Podcast speech without the wake phrase is ignored', !(await audioT()).paused && aiRequests.length === 0);

await page.evaluate(() => window.__sr.emit('hey sada', false));
await page.waitForTimeout(150);
const atPause = await audioT();
check('Wake phrase (interim) pauses the podcast immediately', atPause.paused, `paused at ${atPause.t.toFixed(3)}s`);
check('Status: listening to your question', /Listening to your question/.test(await page.locator('.hint.live').innerText()));

await page.evaluate(() => window.__sr.emit('hey sada what does reliable mean', true));
await page.waitForTimeout(250);
check('Only the question is sent to the AI', aiRequests.length === 1 && aiRequests[0].task === 'explain' && aiRequests[0].payload.question === 'what does reliable mean' && !aiRequests[0].payload.word,
  JSON.stringify(aiRequests[0]?.payload?.question));
check('Question is sent with the paused sentence as context', !!aiRequests[0]?.payload?.sentence);
await page.waitForTimeout(1300);
const tts = await page.evaluate(() => window.__tts);
check('Answer is spoken', tts.spoken.some((x) => x.text === 'reliable.') && tts.spoken.some((x) => x.text.includes('Nadia keeps')), tts.spoken.map((x) => x.text).join(' | ').slice(0, 120));
check('Arabic part spoken with an Arabic voice', tts.spoken.some((x) => x.lang === 'ar-SA' && x.text === 'يُعتمد عليه'));
check('Recognizer was off while Sada spoke', tts.speakingWhileListening === false);
await page.waitForTimeout(500);
check('Recognizer restarts after speaking', await page.evaluate(() => !!window.__sr.current));
check('Status: say Continue', /Say “Continue”/.test(await page.locator('.hint.live').innerText()));
check('Answer also shown on screen and the word saved', /reliable/.test(await page.locator('.ask').innerText().catch(() => '')));

await page.evaluate(() => window.__sr.emit('say continue to keep listening', false));
await page.waitForTimeout(200);
check('Its own spoken answer (echo) does not resume playback', (await audioT()).paused);

await page.evaluate(() => { const a = document.querySelector('audio'); a.addEventListener('play', () => { window.__resumedAt = a.currentTime; }, { once: true }); window.__sr.emit('continue', true); });
await page.waitForTimeout(120);
const after = await audioT();
const resumedAt = await page.evaluate(() => window.__resumedAt);
check('Resume phrase resumes playback', !after.paused);
const mlog = await page.evaluate(() => window.__mlog);
const pauses = mlog.filter((l) => l.startsWith('pause')), plays = mlog.filter((l) => l.startsWith('play'));
const pausedPos = pauses.at(-1)?.split('@')[1], playPos = plays.at(-1)?.split('@')[1];
check('Resumes at the exact position (no seek; play() called at the paused position)', pausedPos === playPos && !mlog.some((l) => l.startsWith('seek')), `pause() at ${pausedPos}s, play() at ${playPos}s; Chromium then reports ${resumedAt.toFixed(3)}s (same jump on a bare <audio>)`);
check('Answer sheet closed on resume', (await page.locator('.ask').count()) === 0);

// Recognition interruption: the browser ends the session on its own
await page.evaluate(() => { const r = window.__sr.current; r.running = false; r.onend(); });
await page.waitForTimeout(500);
check('Recognizer restarts after the browser ends a session', await page.evaluate(() => !!window.__sr.current));

// API failure
aiMode = 'fail';
await page.evaluate(() => window.__sr.emit('hey sada what does careful mean', true));
await page.waitForTimeout(1500);
const t2 = await page.evaluate(() => window.__tts.spoken.map((x) => x.text).join(' | '));
check('API failure: an apology is spoken', /couldn't get an answer/.test(t2));
check('API failure: on-screen error too', (await page.locator('.sheet .error').count()) === 1);
await page.waitForTimeout(500);
await page.evaluate(() => window.__sr.emit('continue', true));
await page.waitForTimeout(150);
check('Resume still works after a failure', !(await audioT()).paused);

// Permission denied
await page.evaluate(() => window.__sr.fail('not-allowed'));
await page.waitForTimeout(200);
check('Microphone denied: hands-free turns off', (await page.getAttribute('[title="Hands-free"]', 'aria-pressed')) === 'false');
check('Microphone denied: explains how to fix it', /needs the microphone/.test(await page.locator('.toast').innerText().catch(() => '')));
check('Playback continues when hands-free stops', !(await audioT()).paused);

// Narrated (device voice) episode: resumes at the same sentence
await page.click('[aria-label="Back to episodes"]');
await page.click('text=Your first job interview');
await page.evaluate(() => { window.__tts.delay = 400; });
await page.click('.play');
await page.waitForTimeout(1500);
await page.click('[aria-label="Turn hands-free on"]');
await page.waitForTimeout(200);
const curIdx = () => page.evaluate(() => [...document.querySelectorAll('.sen')].findIndex((e) => e.classList.contains('cur')));
await page.evaluate(() => window.__sr.emit('hey sada', false));
const i1 = await curIdx();
await page.evaluate(() => { window.__tts.delay = 20; window.__sr.emit('hey sada what does nervous mean', true); });
aiMode = 'ok';
await page.waitForTimeout(1500);
await page.evaluate(() => { window.__tts.delay = 400; window.__sr.emit('continue', true); });
await page.waitForTimeout(100);
const i2 = await curIdx();
check('Narrated episode resumes at the interrupted sentence', i1 > 0 && i1 === i2, `sentence ${i1} → ${i2}`);
check('No page errors', errs.length === 0, errs.join(' | '));
console.log(`\n${results.filter(Boolean).length}/${results.length} browser checks passed`);
await b.close();
