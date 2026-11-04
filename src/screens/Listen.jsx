import React, { useEffect, useMemo, useRef, useState } from 'react';
import { useStore, setProgress, saveWord, deleteWord, logActivity, addEpisode, removeEpisode, setSettings, getState } from '../lib/store.js';
import { EPISODES, LEVELS, levelDistance } from '../lib/episodes.js';
import { splitSentences, tokenize, norm, matchPhrase } from '../lib/text.js';
import { askAI, transcribe } from '../lib/ai.js';
import { ttsSupported, utter, speakOnce, wakeSupported, createRecognizer, speakSegments, earcon, primeEarcon } from '../lib/speech.js';
import { createHandsFree, isPhrase } from '../lib/handsfree.js';
import { recordingSupported, startRecording, micMessage } from '../lib/recorder.js';
import { nativeAvailable, nativePickAudio, onAudioPicked, nativeCancel, nativeState, nativeDrain, nativePlay, nativeResume, nativePause, nativeSeek, nativeAsk, nativeStop, nativeSetPrefs, fractionOfIdx, idxOfFraction } from '../lib/native.js';
import { Icon, Sheet, Ar, Bi, Spinner, LevelTag, toast, DemoTag } from '../ui.jsx';
import { ProgressBar } from './Today.jsx';

export default function Listen({ episodeId, setEpisodeId, go }) {
  const s = useStore();
  const all = [...s.customEpisodes, ...EPISODES];
  const ep = all.find((e) => e.id === episodeId);
  if (ep) return <Player key={ep.id} episode={ep} onBack={() => { setEpisodeId(null); window.scrollTo(0, 0); }} go={go} />;
  return <Library onOpen={setEpisodeId} />;
}

/* ---------------- Library ---------------- */

function Library({ onOpen }) {
  const s = useStore();
  const [q, setQ] = useState('');
  const [lv, setLv] = useState('All');
  const [adding, setAdding] = useState(false);
  const myLevel = s.profile.level;

  const list = useMemo(() => {
    const query = norm(q);
    return [...s.customEpisodes, ...EPISODES]
      .filter((e) => lv === 'All' || e.level === lv)
      .filter((e) => !query || norm(`${e.title} ${e.category} ${e.blurb || ''}`).includes(query))
      .sort((a, b) => (a.mine === b.mine ? levelDistance(a.level, myLevel) - levelDistance(b.level, myLevel) : a.mine ? -1 : 1));
  }, [s.customEpisodes, q, lv, myLevel]);

  return (
    <div className="screen">
      <header className="top">
        <h1 className="h1">Listen</h1>
        <button className="btn secondary small" onClick={() => setAdding(true)}><Icon name="plus" size={18} /> Add episode</button>
      </header>
      <label className="search">
        <Icon name="search" size={18} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search episodes" aria-label="Search episodes" />
      </label>
      <div className="chips scroll" role="group" aria-label="Filter by level">
        {['All', 'A1', 'A2', 'B1', 'B2', 'C1'].map((l) => (
          <button key={l} className={`chip ${lv === l ? 'on' : ''}`} onClick={() => setLv(l)} aria-pressed={lv === l}>
            {l}{l === myLevel ? ' · you' : ''}
          </button>
        ))}
      </div>

      {list.length === 0 ? (
        <p className="muted empty">No episodes match. Try another level, or add your own.</p>
      ) : (
        <ul className="ep-list">
          {list.map((e) => {
            const p = s.progress[e.id];
            return (
              <li key={e.id}>
                <button className="ep-row" onClick={() => onOpen(e.id)}>
                  <span className="ep-meta"><LevelTag level={e.level} /> {e.mine ? 'Your episode' : `${e.category} · ${e.minutes} min`}</span>
                  <span className="ep-title">{e.title}</span>
                  {e.blurb && <span className="ep-blurb">{e.blurb}</span>}
                  {p?.done ? <span className="small t-mint">Finished</span> : p?.idx ? <ProgressBar value={p.idx / (p.total || 1)} /> : null}
                </button>
                {e.mine && (
                  <button className="icon-btn subtle" aria-label={`Delete ${e.title}`} onClick={() => { if (confirm(`Delete “${e.title}”?`)) removeEpisode(e.id); }}>
                    <Icon name="trash" size={18} />
                  </button>
                )}
              </li>
            );
          })}
        </ul>
      )}
      <p className="fine">Sada’s episodes are original and narrated by your phone’s English voice. You can add your own: paste a transcript you have the right to use, with an optional link to the publisher’s audio.</p>
      <AddEpisode open={adding} onClose={() => setAdding(false)} onAdded={(id) => { setAdding(false); onOpen(id); }} />
    </div>
  );
}

function AddEpisode({ open, onClose, onAdded }) {
  const [title, setTitle] = useState('');
  const [text, setText] = useState('');
  const [audioUrl, setAudioUrl] = useState('');
  const [level, setLevel] = useState(getState().profile?.level || 'B1');
  const [err, setErr] = useState('');
  const [fileName, setFileName] = useState('');
  const [importing, setImporting] = useState(false);

  // Android: the picked audio file is copied into the app and served to the player from there
  useEffect(() => {
    if (!nativeAvailable) return undefined;
    onAudioPicked(({ url, name, error }) => {
      setImporting(false);
      if (error === 'cancelled') return;
      if (error || !url) return setErr(error || 'That file could not be imported.');
      setErr(''); setAudioUrl(url); setFileName(name || 'audio file');
      setTitle((t) => t || (name || '').replace(/\.[A-Za-z0-9]{2,5}$/, ''));
    });
    return () => onAudioPicked(null);
  }, []);

  const submit = () => {
    const words = text.trim().split(/\s+/).filter(Boolean).length;
    if (!title.trim()) return setErr('Give the episode a title.');
    if (!audioUrl && words < 20) return setErr('Choose an audio file, or paste at least a few sentences of transcript (20+ words).');
    if (audioUrl && !/^https:\/\/\S+$/i.test(audioUrl.trim())) return setErr('The audio link must start with https://');
    const id = addEpisode({ title: title.trim(), text: text.trim(), audioUrl: audioUrl.trim() || null, level, category: 'Mine', minutes: Math.max(1, Math.round(words / 140)) });
    setTitle(''); setText(''); setAudioUrl(''); setFileName(''); setErr('');
    onAdded(id);
  };

  return (
    <Sheet open={open} onClose={onClose} label="Add your own episode" tall>
      <h2 className="h2">Add your own episode</h2>
      <label className="label" htmlFor="ep-t">Title</label>
      <input id="ep-t" className="input" value={title} onChange={(e) => setTitle(e.target.value)} maxLength={80} />
      {nativeAvailable && (
        <>
          <button className="btn ghost" onClick={() => { setErr(''); setImporting(true); nativePickAudio(); }} disabled={importing}>
            {importing ? 'Importing…' : fileName ? `Audio: ${fileName} (tap to change)` : 'Choose an audio file from your phone'}
          </button>
          <p className="fine">Pick any audio file stored on your phone. A transcript is optional.</p>
        </>
      )}
      <label className="label" htmlFor="ep-x">Transcript {audioUrl ? '(optional)' : ''}</label>
      <textarea id="ep-x" className="input area" value={text} onChange={(e) => setText(e.target.value)} placeholder="Paste the English text here" />
      <label className="label" htmlFor="ep-a">Audio link (optional)</label>
      <input id="ep-a" className="input" value={audioUrl} onChange={(e) => setAudioUrl(e.target.value)} placeholder="https://… .mp3 from the publisher" inputMode="url" disabled={!!fileName} />
      <p className="fine">With an audio link, Sada plays the publisher’s file and shows the transcript beside it. Without one, your phone’s voice reads the transcript and follows along sentence by sentence.</p>
      <label className="label" htmlFor="ep-l">Level</label>
      <select id="ep-l" className="input" value={level} onChange={(e) => setLevel(e.target.value)}>
        {LEVELS.map((l) => <option key={l.id} value={l.id}>{l.id} · {l.name}</option>)}
      </select>
      {err && <p className="error" role="alert">{err}</p>}
      <button className="btn primary big" onClick={submit}>Add and open</button>
    </Sheet>
  );
}

/* ---------------- Player ---------------- */

const RATES = [0.75, 0.9, 1, 1.15];
const CONTINUE = /^(ok |okay )?(continue|resume|go on|keep going|play|carry on)\b/;

function Player({ episode, onBack, go }) {
  const s = useStore();
  const { settings, profile } = s;
  const sentences = useMemo(() => splitSentences(episode.text), [episode.text]);
  const audioMode = !!episode.audioUrl;
  // Android app: the narration, microphone, questions and spoken answers run in the native audio service
  // (double-press Volume Up, automatic end-of-speech, Muse Voice Transcribe, Muse Spark, Android voice).
  const nativeMode = nativeAvailable && !audioMode;
  const [nat, setNat] = useState(null);
  const saved = s.progress[episode.id];

  const [idx, setIdx] = useState(() => (saved && !saved.done ? Math.min(saved.idx || 0, sentences.length - 1) : 0));
  const [playing, setPlaying] = useState(false);
  const [finished, setFinished] = useState(false);
  const [time, setTime] = useState({ t: 0, d: 0 });
  const [ask, setAsk] = useState(null);
  const [voice, setVoice] = useState(null);
  const [handsFree, setHandsFree] = useState(false);
  const [hf, setHf] = useState(null); // hands-free status from the controller
  const [hfLog, setHfLog] = useState([]); // diagnostics: kept in memory on this phone only, cleared when hands-free stops
  const [showHfLog, setShowHfLog] = useState(false);
  const [typed, setTyped] = useState('');

  const idxRef = useRef(idx);
  const playingRef = useRef(false);
  const tokenRef = useRef(0);
  const audioRef = useRef(null);
  const resumeRef = useRef(false);
  const askTokenRef = useRef(0);
  const recRef = useRef(null);
  const caughtAtStart = useRef(s.words.length);
  const sentenceEls = useRef([]);
  const lastSaveRef = useRef(0);

  const caught = useMemo(() => {
    const set = new Set();
    s.words.forEach((w) => { set.add(w.word.toLowerCase()); set.add((w.lemma || '').toLowerCase()); });
    return set;
  }, [s.words]);
  const isCaught = (w) => {
    const k = w.toLowerCase();
    if (caught.has(k)) return true;
    for (const c of caught) if (c.length > 3 && k.startsWith(c) && k.length - c.length <= 3) return true;
    return false;
  };

  /* --- narration engine (device voice, one sentence at a time) --- */
  const speakFrom = useRef(null);
  speakFrom.current = (i) => {
    const synth = window.speechSynthesis;
    const my = ++tokenRef.current;
    synth.cancel();
    if (i >= sentences.length) return finish();
    idxRef.current = i;
    setIdx(i);
    const u = utter(sentences[i], settings.rate);
    // Watchdogs: some Android voices never fire onstart/onend. Don't leave the player "playing" in silence.
    let started = false;
    const words = sentences[i].split(/\s+/).length;
    const startTimer = setTimeout(() => {
      if (tokenRef.current !== my || started || !playingRef.current) return;
      tokenRef.current++;
      synth.cancel();
      playingRef.current = false;
      setPlaying(false);
      toast('Your phone’s English voice didn’t start. Check that a text-to-speech voice is installed, then tap play.');
    }, 6000);
    const endTimer = setTimeout(() => {
      if (tokenRef.current === my && playingRef.current) speakFrom.current(i + 1);
    }, 6000 + (words * 600) / (settings.rate || 1));
    const clear = () => { clearTimeout(startTimer); clearTimeout(endTimer); };
    u.onstart = () => { started = true; clearTimeout(startTimer); };
    u.onend = () => { clear(); if (tokenRef.current === my && playingRef.current) speakFrom.current(i + 1); };
    u.onerror = (e) => {
      clear();
      if (tokenRef.current !== my || e.error === 'interrupted' || e.error === 'canceled') return;
      playingRef.current = false;
      setPlaying(false);
      toast('Your phone’s voice stopped. Tap play to continue.');
    };
    setTimeout(() => { if (tokenRef.current === my && playingRef.current) synth.speak(u); }, 60);
  };

  const finish = () => {
    playingRef.current = false;
    setPlaying(false);
    setFinished(true);
    idxRef.current = 0;
    setIdx(0);
    setProgress(episode.id, { idx: 0, total: sentences.length, done: true });
    logActivity('episodes');
  };

  const play = () => {
    setFinished(false);
    if (nativeMode) {
      if (!playingRef.current) logActivity('listened');
      playingRef.current = true;
      setPlaying(true);
      const st = nativeState();
      if (st && st.loaded && st.episodeId === episode.id && !st.ended) nativeResume();
      else nativePlay({ id: episode.id, title: episode.title, text: episode.text, startFraction: fractionOfIdx(sentences, idxRef.current), lang: profile.explainLang, level: profile.level });
      return;
    }
    if (audioMode) { audioRef.current?.play().catch(() => toast('This audio link could not be played. Check that it points to an audio file.')); return; }
    if (!ttsSupported) return toast('This browser has no English voice. Try Chrome on Android.');
    if (!playingRef.current) logActivity('listened');
    playingRef.current = true;
    setPlaying(true);
    speakFrom.current(idxRef.current);
  };
  const pause = () => {
    if (nativeMode) { playingRef.current = false; setPlaying(false); nativePause(); return; }
    if (audioMode) { audioRef.current?.pause(); return; }
    playingRef.current = false;
    setPlaying(false);
    tokenRef.current++;
    if (ttsSupported) window.speechSynthesis.cancel();
  };
  const isPlaying = () => (audioMode ? !!audioRef.current && !audioRef.current.paused : playingRef.current);
  const seek = (i) => {
    const j = Math.max(0, Math.min(sentences.length - 1, i));
    idxRef.current = j;
    setIdx(j);
    if (nativeMode) { const st = nativeState(); if (st && st.loaded && st.episodeId === episode.id) nativeSeek(fractionOfIdx(sentences, j)); return; }
    if (!audioMode && playingRef.current) speakFrom.current(j);
  };

  useEffect(() => {
    if (!audioMode && !finished) setProgress(episode.id, { idx, total: sentences.length, done: false });
    if (playing) sentenceEls.current[idx]?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }, [idx]); // eslint-disable-line

  useEffect(() => () => {
    tokenRef.current++;
    playingRef.current = false;
    if (ttsSupported) window.speechSynthesis.cancel();
    recRef.current?.cancel();
    if (nativeMode) { const st = nativeState(); if (st && st.episodeId === episode.id) nativeStop(); }
  }, []); // eslint-disable-line

  // In audio mode there are no sentence timings; estimate the current sentence from the playback position.
  const currentIdx = () => (audioMode && time.d ? Math.min(sentences.length - 1, Math.floor((time.t / time.d) * sentences.length)) : idxRef.current);

  /* --- asking --- */
  const askAbout = async ({ word, question, sentenceIdx }) => {
    resumeRef.current = resumeRef.current || isPlaying();
    pause();
    const si = sentenceIdx ?? currentIdx();
    const sentence = sentences[si] || '';
    const before = sentences[si - 1] || '';
    const my = ++askTokenRef.current;
    setAsk({ status: 'loading', word, question, sentence });
    try {
      const r = await askAI('explain', { word, question, sentence, before, level: profile.level, lang: profile.explainLang });
      if (askTokenRef.current !== my) return null;
      // A spoken question may be about a word from a moment ago: keep the sentence that actually contains it.
      const ctx = r?.word ? sentenceWith(sentences, r.word, si) ?? sentence : sentence;
      let savedInfo = null;
      if (r?.is_vocab && r.word && !r.demo_unavailable && getState().settings.autoSave) savedInfo = save(r, ctx);
      logActivity('asked');
      setAsk({ status: 'done', word, question, sentence: ctx, result: r, saved: savedInfo });
      return r;
    } catch (e) {
      if (askTokenRef.current !== my) return null;
      setAsk({ status: 'error', word, question, sentence, error: e.message, sentenceIdx: si });
      return null;
    }
  };

  const save = (r, sentence) =>
    saveWord({
      ...r,
      context: { sentence, title: episode.title, episodeId: episode.id, at: Date.now() },
      source: { type: 'episode', title: episode.title, episodeId: episode.id },
    });

  const continueListening = () => {
    hfRef.current?.onManualResume();
    askTokenRef.current++;
    setAsk(null);
    setTyped('');
    if (nativeMode) { resumeRef.current = false; const st = nativeState(); if (st && st.episodeId === episode.id && !st.playing) { playingRef.current = true; setPlaying(true); nativeResume(); } return; }
    if (resumeRef.current) { resumeRef.current = false; play(); }
  };

  const handleUtterance = (text) => {
    const t = norm(text);
    if (!t) return toast('Didn’t catch that. Try again a little closer to the mic.');
    if (CONTINUE.test(t) || isPhrase(text, settings.resumePhrase) || /^(كمل|واصل)/.test(text.trim())) return continueListening();
    if (/^(pause|stop|wait)$/.test(t)) { resumeRef.current = false; return pause(); }
    askAbout({ question: text });
  };

  /* --- push-to-talk via Muse Voice Transcribe --- */
  const startVoice = async () => {
    if (nativeMode) { resumeRef.current = false; nativeAsk(); return; }
    if (handsFree) return toast(`Hands-free is on: just say “${settings.wakePhrase}” and your question.`);
    if (!recordingSupported) return toast('This browser can’t record audio. Type your question instead.');
    resumeRef.current = resumeRef.current || isPlaying();
    pause();
    setVoice({ phase: 'rec', level: 0 });
    try {
      recRef.current = await startRecording({
        maxMs: 15000,
        onLevel: (l) => setVoice((v) => (v?.phase === 'rec' ? { ...v, level: l } : v)),
        onAutoStop: () => stopVoice(),
      });
    } catch (e) {
      recRef.current = null;
      setVoice(null);
      toast(e?.message || micMessage());
    }
  };
  const stopVoice = async () => {
    const rec = recRef.current;
    if (!rec) return;
    recRef.current = null;
    let clip;
    try { clip = rec.stop(); } catch (e) { setVoice(null); return toast(e.message || micMessage()); }
    const { base64, seconds } = clip;
    if (seconds < 0.5) { setVoice(null); return toast('Tap the mic, ask your question, then tap again to send.'); }
    setVoice({ phase: 'sending' });
    try {
      const nearby = [sentences[currentIdx() - 1], sentences[currentIdx()]].join(' ');
      const keywords = [...new Set(tokenize(nearby).filter((t) => t.w && t.w.length > 3).map((t) => t.w.toLowerCase()))].slice(0, 30);
      const { text, source } = await transcribe(base64, { keywords, languages: ['English', 'Arabic'] });
      setVoice(null);
      if (source !== 'meta') toast('Demo preview: your recording wasn’t transcribed. Sada picked a word from the current sentence instead.');
      handleUtterance(text);
    } catch (e) {
      setVoice(null);
      toast(e.message);
    }
  };
  const cancelVoice = () => { recRef.current?.cancel(); recRef.current = null; setVoice(null); };

  /* --- hands-free: wake phrase → question → spoken answer → resume phrase (see lib/handsfree.js) --- */
  const hfRef = useRef(null);
  const live = useRef({});
  live.current = { isPlaying, pause, play, askAbout, settings, profile, audioMode };
  useEffect(() => {
    if (!handsFree) return;
    let wakeLock = null;
    const debug = (ev) => setHfLog((log) => {
      const line = { ...ev, at: new Date().toLocaleTimeString('en-GB') };
      const last = log[log.length - 1];
      // successive partial results of one utterance replace each other
      if (ev.type === 'heard' && !ev.final && last?.type === 'heard' && !last.final && last.outcome === ev.outcome) return [...log.slice(0, -1), line];
      return [...log, line].slice(-25);
    });
    const lock = async () => { try { wakeLock = await navigator.wakeLock?.request('screen'); } catch {} };
    const rec = createRecognizer({
      onResult: (t, fin) => ctrl.onTranscript(t, fin),
      onEnd: () => ctrl.onRecognitionEnd(),
      onError: (code) => ctrl.onRecognitionError(code),
      onEvent: debug,
    });
    const ctrl = createHandsFree({
      recognizer: rec,
      getSettings: () => ({ ...live.current.settings, explainLang: live.current.profile.explainLang }),
      player: {
        isPlaying: () => live.current.isPlaying(),
        pause: () => {
          const pos = live.current.audioMode ? { kind: 'audio', time: audioRef.current?.currentTime || 0 } : { kind: 'tts', idx: idxRef.current };
          live.current.pause();
          resumeRef.current = true;
          return pos;
        },
        resume: (pos) => {
          resumeRef.current = false;
          askTokenRef.current++;
          setAsk(null);
          if (pos.kind === 'audio' && audioRef.current) {
            if (Math.abs(audioRef.current.currentTime - pos.time) > 0.05) audioRef.current.currentTime = pos.time;
          } else if (pos.kind === 'tts') {
            idxRef.current = pos.idx;
            setIdx(pos.idx);
          }
          live.current.play();
        },
      },
      ask: (q) => live.current.askAbout({ question: q }),
      speak: (script) => speakSegments(script),
      earcon,
      onDebug: debug,
      onStatus: (st) => {
        setHf(st);
        if (st.kind === 'no-question') toast('No question heard. Back to the episode.');
        if (st.kind === 'speak-failed') toast('Sada couldn’t speak the answer. It’s on the screen.');
        if (st.kind === 'fatal') { setHandsFree(false); toast(HF_FATAL[st.code] || HF_FATAL.default); }
      },
    });
    hfRef.current = ctrl;
    rec.modeReady.then((m) => setHf((h) => ({ ...(h || {}), mode: m })));
    const onVis = () => { ctrl.onVisibility(document.hidden); if (!document.hidden) lock(); };
    document.addEventListener('visibilitychange', onVis);
    lock();
    ctrl.start();
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      ctrl.stop();
      hfRef.current = null;
      setHf(null);
      setHfLog([]);
      wakeLock?.release?.().catch(() => {});
    };
  }, [handsFree]); // eslint-disable-line

  const cycleRate = () => {
    const next = RATES[(RATES.indexOf(settings.rate) + 1) % RATES.length] || 1;
    setSettings({ rate: next });
    if (playingRef.current) setTimeout(() => speakFrom.current(idxRef.current), 0);
  };

  const newlyCaught = Math.max(0, s.words.length - caughtAtStart.current);

  /* --- Android app: follow the native audio service (position, play state, questions and answers) --- */
  useEffect(() => {
    if (!nativeMode) return undefined;
    nativeSetPrefs(profile.explainLang, profile.level);
    const tick = () => {
      const st = nativeState();
      if (!st) return;
      setNat(st);
      const mine = st.episodeId === episode.id && st.loaded;
      if (mine) {
        if (st.playing !== playingRef.current) { playingRef.current = st.playing; setPlaying(st.playing); }
        if (st.durationMs > 0) {
          const j = Math.min(sentences.length - 1, idxOfFraction(sentences, st.positionMs / st.durationMs));
          if (j !== idxRef.current) { idxRef.current = j; setIdx(j); }
        }
        if (st.ended) { nativeStop(); finish(); }
      }
      // questions and answers that arrived (also while the screen was off): save words, show the last answer
      const events = nativeDrain();
      events.forEach((ev, n) => {
        if (ev.type !== 'qa') return;
        const si = Math.min(sentences.length - 1, idxRef.current);
        const sentence = ev.sentence || sentences[si] || '';
        const r = {
          word: ev.term || '', lemma: ev.term || '', pos: '', ipa: '',
          meaning_en: ev.answer_en || '', meaning_ar: ev.answer_ar || '', answer_en: ev.answer_en || '', answer_ar: ev.answer_ar || '',
          example: ev.example || '', is_vocab: !!(ev.isVocab && ev.term), _source: 'meta',
        };
        let savedInfo = null;
        if (r.is_vocab && getState().settings.autoSave) savedInfo = save(r, sentence);
        logActivity('asked');
        if (n === events.length - 1) setAsk({ status: 'done', word: r.word, question: ev.question, sentence, result: r, saved: savedInfo });
      });
      // the podcast resumed (Continue / Volume Up): close the answer sheet
      if (mine && st.phase === 'LISTENING' && st.playing) setAsk((a) => (a && a.status === 'done' ? null : a));
    };
    tick();
    const t = setInterval(tick, 500);
    return () => clearInterval(t);
  }, [nativeMode, profile.explainLang, profile.level]); // eslint-disable-line

  return (
    <div className="player">
      <header className="player-top">
        <button className="icon-btn" onClick={onBack} aria-label="Back to episodes"><Icon name="back" /></button>
        <div className="player-title">
          <span className="ep-title sm">{episode.title}</span>
          <span className="small muted"><LevelTag level={episode.level} /> {audioMode ? 'Publisher audio' : 'Narrated'}</span>
        </div>
        {wakeSupported && !nativeMode ? (
          <button className={`icon-btn ${handsFree ? 'on' : ''}`} onClick={() => { primeEarcon(); setHandsFree(!handsFree); }} aria-pressed={handsFree}
            aria-label={handsFree ? 'Turn hands-free off' : 'Turn hands-free on'} title="Hands-free">
            <Icon name="ear" />
          </button>
        ) : <span style={{ width: 40 }} />}
      </header>

      {handsFree && (
        <div className="hint live">
          <p role="status" aria-live="polite">{hfText(hf, settings)}</p>
          <button className="link small" onClick={() => setShowHfLog(!showHfLog)} aria-expanded={showHfLog}>{showHfLog ? 'Hide' : 'Show'} what Sada hears</button>
          {showHfLog && <HfLog log={hfLog} />}
        </div>
      )}
      {nativeMode && nat?.preparing && <p className="hint live" role="status">{nat.preparing}</p>}
      {nativeMode && nat?.error && <p className="error small" role="alert">{nat.error}</p>}
      {nativeMode && nat?.episodeId === episode.id && nat.phase && !['LISTENING', 'STOPPED'].includes(nat.phase) && <p className="hint live" role="status" aria-live="polite">{nat.stateText}</p>}
      {!handsFree && s.words.length === 0 && <p className="hint">{nativeMode ? 'Tap any word you don’t know, or double-press Volume Up and ask by voice.' : 'Tap any word you don’t know. Or tap the mic and ask.'}</p>}

      <article className="transcript" lang="en">
        {sentences.length === 0 && <p className="hint">No transcript for this audio. Tap the mic to ask about anything you hear.</p>}
        {sentences.map((sen, i) => (
          <p key={i} ref={(el) => (sentenceEls.current[i] = el)} className={`sen ${!audioMode && i === idx ? 'cur' : ''} ${!audioMode && i < idx ? 'past' : ''}`}>
            {!audioMode && (
              <button className="sen-jump" onClick={() => seek(i)} aria-label={`Play from sentence ${i + 1}`}><Icon name="play" size={10} fill /></button>
            )}
            {tokenize(sen).map((t, j) =>
              t.w ? (
                <button key={j} className={`w ${isCaught(t.w) ? 'caught' : ''}`} onClick={() => askAbout({ word: t.w, sentenceIdx: i })}>{t.raw}</button>
              ) : <span key={j}>{t.raw}</span>
            )}
          </p>
        ))}
        {finished && (
          <div className="card done-card">
            <p className="h3">Episode finished.</p>
            <p className="muted">{newlyCaught > 0 ? `You caught ${newlyCaught} new word${newlyCaught === 1 ? '' : 's'}. Practice them while they’re fresh.` : 'No new words this time. Nice listening.'}</p>
            {newlyCaught > 0 && <button className="btn primary" onClick={() => go('practice')}>Practice now</button>}
          </div>
        )}
      </article>

      <footer className="controls">
        {audioMode ? (
          <>
            <audio ref={audioRef} src={episode.audioUrl} preload="metadata"
              onPlay={() => { setPlaying(true); logActivity('listened'); }} onPause={() => setPlaying(false)}
              onEnded={finish}
              onLoadedMetadata={(e) => { const t = saved?.time || 0; if (t && !saved?.done) e.currentTarget.currentTime = t; setTime({ t, d: e.currentTarget.duration || 0 }); }}
              onTimeUpdate={(e) => {
                const t = e.currentTarget.currentTime;
                setTime({ t, d: e.currentTarget.duration || 0 });
                if (Math.abs(t - lastSaveRef.current) > 5) { lastSaveRef.current = t; setProgress(episode.id, { time: t, idx: Math.round(t), total: Math.round(e.currentTarget.duration || 1), done: false }); }
              }}
              onError={() => toast('This audio link could not be loaded.')} />
            <input type="range" className="scrub" min={0} max={time.d || 0} step={1} value={time.t}
              onChange={(e) => { if (audioRef.current) audioRef.current.currentTime = Number(e.target.value); }} aria-label="Playback position" />
          </>
        ) : (
          <button className="scrub-bar" aria-label="Jump in episode"
            onClick={(e) => { const r = e.currentTarget.getBoundingClientRect(); seek(Math.floor(((e.clientX - r.left) / r.width) * sentences.length)); }}>
            <span style={{ width: `${((idx + (playing ? 0.5 : 0)) / sentences.length) * 100}%` }} />
          </button>
        )}
        <div className="ctrl-row">
          <button className="rate" onClick={cycleRate} aria-label={`Speed ${settings.rate}x`} disabled={audioMode || nativeMode}>{audioMode || nativeMode ? '' : `${settings.rate}×`}</button>
          <button className="icon-btn" onClick={() => (audioMode ? audioRef.current && (audioRef.current.currentTime -= 10) : seek(idx - 1))} aria-label={audioMode ? 'Back 10 seconds' : 'Previous sentence'}><Icon name="prev" /></button>
          <button className="play" onClick={() => { if (!playing) hfRef.current?.onManualResume(); playing ? pause() : play(); }} aria-label={playing ? 'Pause' : 'Play'}>
            <Icon name={playing ? 'pause' : 'play'} size={28} fill />
          </button>
          <button className="icon-btn" onClick={() => (audioMode ? audioRef.current && (audioRef.current.currentTime += 10) : seek(idx + 1))} aria-label={audioMode ? 'Forward 10 seconds' : 'Next sentence'}><Icon name="next" /></button>
          <button className={`mic ${voice?.phase === 'rec' ? 'rec' : ''}`} onClick={() => (voice?.phase === 'rec' ? stopVoice() : !voice && startVoice())}
            aria-label={voice?.phase === 'rec' ? 'Send question' : 'Ask with your voice'} style={voice?.phase === 'rec' ? { '--lvl': voice.level } : undefined}>
            {voice?.phase === 'sending' ? <span className="spinner light" /> : <Icon name={voice?.phase === 'rec' ? 'send' : 'mic'} />}
          </button>
        </div>
        {voice?.phase === 'rec' && (
          <div className="rec-bar" role="status">
            <span className="rec-dot" /> Listening. Ask your question, then tap send.
            <button className="link" onClick={cancelVoice}>Cancel</button>
          </div>
        )}
        {voice?.phase === 'sending' && <div className="rec-bar" role="status">Understanding your question…</div>}
        {nativeMode && nat?.phase === 'ASKING' && (
          <div className="rec-bar" role="status">
            <span className="rec-dot" /> Listening to your question…
            <button className="link" onClick={nativeCancel}>Cancel</button>
          </div>
        )}
      </footer>

      <AskSheet ask={ask} lang={profile.explainLang} typed={typed} setTyped={setTyped}
        onClose={continueListening} onContinue={continueListening}
        onRetry={() => askAbout({ word: ask.word, question: ask.question, sentenceIdx: ask.sentenceIdx })}
        onFollowUp={(q) => askAbout({ question: q, sentenceIdx: sentences.indexOf(ask.sentence) })}
        onSave={() => { const r = save(ask.result, ask.sentence); setAsk({ ...ask, saved: r }); }}
        onUndo={() => { deleteWord(ask.saved.id); setAsk({ ...ask, saved: null, undone: true }); }} />
    </div>
  );
}

const HF_FATAL = {
  'not-allowed': 'Hands-free needs the microphone. Allow it for this site in Chrome (icon left of the address → Permissions), or open Sada directly in Chrome rather than inside another app.',
  'service-not-allowed': 'This browser’s speech recognition isn’t available here. Hands-free works in Chrome on Android.',
  'audio-capture': 'No microphone available. Another app may be using it.',
  'language-not-supported': 'English speech recognition isn’t available in this browser.',
  default: 'Hands-free stopped.',
};

function hfText(hf, settings) {
  const wake = `“${settings.wakePhrase}”`, cont = `“${settings.resumePhrase}”`;
  const k = hf?.kind;
  if (k === 'hidden') return 'Screen off or app in the background: the browser may stop listening. Keep Sada open on screen.';
  if (k === 'network-down') return 'Hands-free can’t reach the speech service. Check your connection; it keeps retrying.';
  switch (hf?.state) {
    case 'question': return 'Listening to your question…';
    case 'thinking': return 'Thinking…';
    case 'speaking': return 'Speaking the answer…';
    case 'resume': return k === 'answer-failed' ? `No answer this time. Say ${cont} or ask again.` : `Say ${cont} to keep listening, or ${wake} to ask more.`;
    default: return `Hands-free on. Say ${wake} and your question.${hf?.mode === 'on-device' ? ' (on-device recognition)' : ''}`;
  }
}

const OUTCOME = {
  'wake-phrase': 'wake phrase ✓', 'not-wake-phrase': 'not the wake phrase', 'question-part': 'question', 'question-partial': 'question…',
  'resume-phrase': 'resume phrase ✓', 'not-resume-phrase': 'not the resume phrase', 'own-voice': 'Sada’s own voice, ignored',
  ignored: 'ignored (Sada was speaking)', 'ignored-thinking': 'ignored (waiting for the answer)',
};
function hfLine(e) {
  switch (e.type) {
    case 'heard': return `${e.final ? 'Heard' : 'Hearing'} “${String(e.text).slice(0, 80)}” → ${OUTCOME[e.outcome] || e.outcome}`;
    case 'state': return `State: ${e.from} → ${e.to}`;
    case 'question': return `Question sent: “${String(e.text).slice(0, 80)}”`;
    case 'mode': return `Recognition: ${e.mode === 'on-device' ? 'on this phone' : 'Chrome speech service'}`;
    case 'session-start': return `Listening session ${e.session} started`;
    case 'session-closed': return `Session ${e.session} ended (${e.why})`;
    case 'start-retry': return 'Recognizer busy, retrying';
    case 'error': return `Recognition error: ${e.code}`;
    default: return null;
  }
}
function HfLog({ log }) {
  const lines = log.map((e) => ({ at: e.at, text: hfLine(e) })).filter((l) => l.text);
  return (
    <div className="hf-log" aria-label="What Sada hears">
      {lines.length === 0 ? <p className="muted small">Nothing yet. Say something.</p> : lines.map((l, i) => <p key={i}><span className="muted">{l.at}</span> {l.text}</p>)}
      <p className="fine">Stays on this phone, isn’t saved, and is cleared when hands-free stops.</p>
    </div>
  );
}

function sentenceWith(sentences, word, from) {
  const re = new RegExp(`\\b${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}`, 'i');
  for (let d = 0; d < sentences.length; d++) {
    for (const k of [from - d, from + d]) if (k >= 0 && k < sentences.length && re.test(sentences[k])) return sentences[k];
  }
  return null;
}

function AskSheet({ ask, lang, typed, setTyped, onClose, onContinue, onRetry, onFollowUp, onSave, onUndo }) {
  const r = ask?.result;
  return (
    <Sheet open={!!ask} onClose={onClose} label="Word explanation">
      {ask?.status === 'loading' && (
        <div className="ask-loading">
          <span className="ask-q">{ask.word ? <b className="caught-word">{ask.word}</b> : <>“{ask.question}”</>}</span>
          <span className="row gap muted"><Spinner /> Reading the sentence…</span>
        </div>
      )}
      {ask?.status === 'error' && (
        <div>
          <p className="error" role="alert">{ask.error}</p>
          <div className="row gap"><button className="btn secondary grow" onClick={onRetry}>Try again</button><button className="btn ghost grow" onClick={onContinue}>Keep listening</button></div>
        </div>
      )}
      {ask?.status === 'done' && r && (
        <div className="ask">
          <DemoTag source={r._source} />
          {r.demo_unavailable ? (
            <div className="unavailable">
              <p className="h3">No demo answer for “{r.word}”</p>
              <p className="muted">This preview isn’t connected to Meta AI, so it only knows sample answers for some words. Nothing was saved.</p>
              {r.sample_words && <p className="small">Words with sample answers: {r.sample_words}</p>}
            </div>
          ) : r.is_vocab ? (
            <>
              <div className="ask-head">
                <button className="ask-word" onClick={() => speakOnce(r.lemma || r.word)} aria-label={`Hear ${r.word}`}>
                  {r.word} <Icon name="speaker" size={20} />
                </button>
                <span className="muted small">{[r.pos, r.ipa && `/${r.ipa.replace(/^\/|\/$/g, '')}/`].filter(Boolean).join('  ')}</span>
              </div>
              {lang !== 'en' && r.meaning_ar && <Ar className="big-ar">{r.meaning_ar}</Ar>}
              {lang !== 'ar' && r.meaning_en && <p className="def">{r.meaning_en}</p>}
              {(r.in_context_ar || r.in_context_en) && <p className="in-ctx"><Bi ar={r.in_context_ar} en={r.in_context_en} lang={lang} /></p>}
              {r.example && <p className="example">“{r.example}”</p>}
              <div className="saved-line">
                {ask.saved ? (
                  ask.saved.isNew ? (
                    <><span className="t-mint"><Icon name="check" size={16} /> Saved to your words</span><button className="link" onClick={onUndo}>Undo</button></>
                  ) : <span className="t-sun">Already in your words. Sada will bring it back sooner.</span>
                ) : <button className="btn secondary small" onClick={onSave}>Save word</button>}
              </div>
            </>
          ) : (
            <p className="def"><Bi ar={r.answer_ar} en={r.answer_en} lang={lang} /></p>
          )}
          <form className="follow" onSubmit={(e) => { e.preventDefault(); if (typed.trim()) { onFollowUp(typed.trim()); setTyped(''); } }}>
            <input className="input" value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="Ask something else about this sentence" aria-label="Follow-up question" />
            <button className="icon-btn" aria-label="Ask"><Icon name="send" /></button>
          </form>
          <button className="btn primary big" onClick={onContinue}>Continue listening</button>
        </div>
      )}
    </Sheet>
  );
}
