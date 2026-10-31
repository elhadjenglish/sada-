// Run against a production bundle served at http://localhost:8765 with tone.wav. Requires Playwright. SIMULATED recognizer and voice.
// SIMULATED browser test: real Sada UI; the speech recognizer is a fake that behaves like Android Chrome
// (session ends after each final result, the app must restart it). Speech synthesis is faked. Mic button uses
// Chromium's fake microphone. Proves Sada's handling, not real Android recognition.
import { chromium } from '/home/claude/.npm-global/lib/node_modules/playwright/index.mjs';
const res = []; const check = (n, ok, d = '') => { res.push(ok); console.log(`${ok ? 'PASS' : 'FAIL'}  ${n}${d ? '  — ' + d : ''}`); };
const init = () => {
  localStorage.setItem('sada:v1', JSON.stringify({ version: 1, profile: { name: 'H', level: 'B1', explainLang: 'en', done: true },
    settings: { wakePhrase: 'Hey Sada', resumePhrase: 'Continue', autoSave: true, rate: 1 }, words: [], progress: {}, activity: {}, reviewSkippedOn: null,
    customEpisodes: [{ id: 'a', mine: true, title: 'Tone test episode', level: 'B1', category: 'Mine', minutes: 1, audioUrl: 'http://localhost:8765/tone.wav', text: 'She explained that she was reliable. The manager listened carefully.' }] }));
  window.__sr = { instances: [], get current() { return this.instances.filter((r) => r.running).at(-1); } };
  class FakeSR { constructor() { this.running = false; window.__sr.instances.push(this); }
    start() { this.running = true; setTimeout(() => this.onstart?.(), 1); } abort() { if (!this.running) return; this.running = false; setTimeout(() => this.onend?.(), 5); } stop() { this.abort(); } }
  window.webkitSpeechRecognition = window.SpeechRecognition = FakeSR;
  // Android-like: each utterance = growing partials, one final, then the session ends.
  window.__sr.utter = async (sentence) => {
    const r = window.__sr.current; if (!r) return 'no-session';
    const w = sentence.split(' ');
    for (let i = 1; i <= w.length; i++) { r.onresult({ resultIndex: 0, results: [Object.assign([{ transcript: w.slice(0, i).join(' ') }], { isFinal: false })] }); await new Promise((x) => setTimeout(x, 20)); }
    if (!r.running) return 'aborted';
    r.onresult({ resultIndex: 0, results: [Object.assign([{ transcript: sentence }], { isFinal: true })] });
    if (r.running) { r.running = false; r.onend?.(); }
    return 'ok';
  };
  window.__tts = { spoken: [] };
  window.SpeechSynthesisUtterance = class { constructor(t) { this.text = t; } };
  Object.defineProperty(window, 'speechSynthesis', { configurable: true, value: { speak(u) { window.__tts.spoken.push(u.text); setTimeout(() => { u.onstart?.(); setTimeout(() => u.onend?.(), 50); }, 5); }, cancel() {}, getVoices: () => [{ lang: 'en-US', name: 'EN' }] } });
  window.__tones = 0;
  const O = window.AudioContext.prototype.createOscillator;
  window.AudioContext.prototype.createOscillator = function () { window.__tones++; return O.call(this); };
};
const b = await chromium.launch({ args: ['--autoplay-policy=no-user-gesture-required', '--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'] });
const ctx = await b.newContext({ viewport: { width: 393, height: 851 }, permissions: ['microphone'] });
await ctx.addInitScript(init);
const page = await ctx.newPage();
const errs = []; page.on('pageerror', (e) => errs.push(e.message));
const ai = []; const asr = [];
await page.route('**/api/ai', (r) => { ai.push(JSON.parse(r.request().postData())); r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ source: 'meta', result: { is_vocab: true, word: 'reliable', lemma: 'reliable', pos: 'adjective', ipa: '', meaning_en: 'you can trust them', meaning_ar: 'موثوق', in_context_en: 'Here it means trustworthy.', in_context_ar: '', example: '', answer_en: '', answer_ar: '' } }) }); });
await page.route('**/api/transcribe', (r) => { asr.push(1); r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ source: 'meta', text: 'What does reliable mean?' }) }); });
await page.goto('http://localhost:8765/index.html');
await page.click('.tab:has-text("Listen")'); await page.click('text=Tone test episode'); await page.click('.play'); await page.waitForTimeout(1000);
const paused = () => page.evaluate(() => document.querySelector('audio').paused);

// Mic button first (hands-free off)
await page.click('.mic'); await page.waitForTimeout(1200); await page.click('.mic'); await page.waitForTimeout(1500);
check('Mic button still works (records, Muse Voice Transcribe, explanation)', asr.length === 1 && ai.length === 1 && ai[0].payload.question === 'What does reliable mean?');
await page.click('text=Continue listening'); await page.waitForTimeout(300);
ai.length = 0;

await page.click('[aria-label="Turn hands-free on"]'); await page.waitForTimeout(150);
await page.click('text=Show what Sada hears');
const tones0 = await page.evaluate(() => window.__tones);

for (const s of ['Good morning', 'She explained that she was reliable', 'hey Sam are you there']) await page.evaluate((x) => window.__sr.utter(x), s), await page.waitForTimeout(400);
check('"Good morning" and unrelated sentences do not pause', !(await paused()));
check('No Sada tone for unrelated words', (await page.evaluate(() => window.__tones)) === tones0);
check('Sessions restarted after each utterance (Android behaviour)', (await page.evaluate(() => window.__sr.instances.length)) >= 4 && (await page.evaluate(() => !!window.__sr.current)));
const log1 = await page.locator('.hf-log').innerText();
check('Diagnostics show what was heard and why it was ignored', /Heard “Good morning” → not the wake phrase/.test(log1), log1.split('\n').find((l) => l.includes('Good morning')));

await page.evaluate(() => window.__sr.utter('Hey'));
await page.waitForTimeout(300);
await page.evaluate(() => window.__sr.utter('Sada'));
await page.waitForTimeout(200);
check('"Hey" and "Sada" in separate sessions → pauses', await paused());
check('Sada tone played once, only after the wake phrase', (await page.evaluate(() => window.__tones)) === tones0 + 1);
await page.waitForTimeout(400); // the user speaks after Android restarts listening
await page.evaluate(() => window.__sr.utter('what does reliable mean'));
await page.waitForTimeout(2600);
check('Question captured from the next session and sent alone', ai.length === 1 && ai[0].payload.question === 'what does reliable mean', JSON.stringify(ai[0]?.payload?.question));
check('Answer spoken', (await page.evaluate(() => window.__tts.spoken.join(' | '))).includes('you can trust them'));
await page.waitForTimeout(500);
await page.evaluate(() => window.__sr.utter('continue'));
await page.waitForTimeout(300);
check('"Continue" resumes', !(await paused()));

await page.evaluate(() => window.__sr.utter('Hey, Sada, what does reliable mean?'));
await page.waitForTimeout(400);
check('"Hey, Sada, what does reliable mean?" pauses and asks', (await paused()) && ai.length === 2 && ai[1].payload.question === 'what does reliable mean', JSON.stringify(ai[1]?.payload?.question));
await page.waitForTimeout(800);
await page.evaluate(() => window.__sr.utter('continue')); await page.waitForTimeout(300);
await page.evaluate(() => window.__sr.utter('Hey Sarah'));
await page.waitForTimeout(300);
check('"Hey Sarah" (common mishearing) pauses', await paused());
const log2 = await page.locator('.hf-log').innerText();
check('Diagnostics show the wake phrase decision', /→ wake phrase ✓/.test(log2));
// recognizer error without "end" (restart race)
await page.waitForTimeout(8500); // no question → resumes
await page.evaluate(() => { const r = window.__sr.current; r.onerror({ error: 'no-speech' }); });
await page.waitForTimeout(2200);
check('Error without an "end" event: listening recovers', await page.evaluate(() => { const r = window.__sr.current; return !!r && r.running; }));
check('No page errors', errs.length === 0, errs.join(' | '));
console.log(`\n${res.filter(Boolean).length}/${res.length} checks passed`);
await b.close();
