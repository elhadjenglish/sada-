import { useSyncExternalStore } from 'react';
import { newSrs, review } from './srs.js';

// Stage 1 persistence: the learner's device (localStorage). Nothing leaves the device.
// Every stored copy carries a schema version; before any future migration rewrites it,
// the previous copy is kept under a backup key so vocabulary can't be lost by an upgrade.
const KEY = 'sada:v1';
export const SCHEMA_VERSION = 1;
const defaults = {
  version: SCHEMA_VERSION,
  profile: null,
  settings: { wakePhrase: 'Hey Sada', resumePhrase: 'Continue', handsFree: false, autoSave: true, rate: 1 },
  words: [],
  progress: {},
  activity: {},
  customEpisodes: [],
  reviewSkippedOn: null,
};

function safeSet(key, value) {
  try { localStorage.setItem(key, value); return true; } catch { return false; }
}

// Ordered upgrade steps: MIGRATIONS[n] turns version n into n + 1. Empty until the shape changes.
const MIGRATIONS = {};

function load() {
  let raw = null;
  try { raw = localStorage.getItem(KEY); } catch {}
  if (!raw) return structuredClone(defaults);
  let p;
  try {
    p = JSON.parse(raw);
    if (!p || typeof p !== 'object') throw new Error('not an object');
  } catch {
    // Never overwrite data we couldn't read: park it so it can be recovered.
    safeSet(`${KEY}:unreadable:${Date.now()}`, raw);
    return structuredClone(defaults);
  }
  let v = Number.isInteger(p.version) ? p.version : 1;
  if (v < SCHEMA_VERSION) {
    safeSet(`${KEY}:backup:v${v}`, raw);
    while (v < SCHEMA_VERSION && MIGRATIONS[v]) { p = MIGRATIONS[v](p); v += 1; }
  }
  return {
    ...structuredClone(defaults),
    ...p,
    version: SCHEMA_VERSION,
    // Repair rather than drop: a word missing its review state just starts fresh.
    words: Array.isArray(p.words) ? p.words.filter((w) => w && typeof w.word === 'string').map((w) => (w.srs ? w : { ...w, srs: newSrs() })) : [],
    settings: { ...defaults.settings, ...(p.settings || {}) },
  };
}

let state = load();
let storageOk = true;
const listeners = new Set();

function persist() {
  storageOk = safeSet(KEY, JSON.stringify(state));
}

export const getState = () => state;
export const storageWorks = () => storageOk;

export function update(fn) {
  state = fn(state);
  persist();
  listeners.forEach((l) => l());
}

export function useStore() {
  return useSyncExternalStore(
    (cb) => { listeners.add(cb); return () => listeners.delete(cb); },
    () => state
  );
}

export const todayKey = (d = new Date()) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export function logActivity(kind, n = 1) {
  update((s) => {
    const k = todayKey();
    const day = { ...(s.activity[k] || {}) };
    day[kind] = (day[kind] || 0) + n;
    return { ...s, activity: { ...s.activity, [k]: day } };
  });
}

export function streakOf(activity) {
  const d = new Date();
  if (!activity[todayKey(d)]) d.setDate(d.getDate() - 1);
  let count = 0;
  while (activity[todayKey(d)]) {
    count++;
    d.setDate(d.getDate() - 1);
  }
  return count;
}

const uid = () => Math.random().toString(36).slice(2, 9) + Date.now().toString(36);
const keyOf = (w) => String(w || '').trim().toLowerCase();

// Saves a newly asked word, or strengthens the struggle signal if it was already saved.
export function saveWord(entry) {
  let id;
  let isNew = false;
  update((s) => {
    const k1 = keyOf(entry.lemma || entry.word);
    const k2 = keyOf(entry.word);
    const i = s.words.findIndex((w) => [keyOf(w.lemma), keyOf(w.word)].some((k) => k === k1 || k === k2));
    if (i >= 0) {
      const w = s.words[i];
      id = w.id;
      const contexts = w.contexts || [];
      const addCtx = entry.context && !contexts.some((c) => c.sentence === entry.context.sentence);
      const words = [...s.words];
      words[i] = {
        ...w,
        asks: (w.asks || 1) + 1,
        lastAskedAt: Date.now(),
        contexts: addCtx ? [...contexts, entry.context].slice(-5) : contexts,
        srs: { ...w.srs, due: Math.min(w.srs.due, Date.now()) },
      };
      return { ...s, words };
    }
    id = uid();
    isNew = true;
    const w = {
      id,
      word: entry.word,
      lemma: entry.lemma || entry.word,
      pos: entry.pos || '',
      ipa: entry.ipa || '',
      meaning_en: entry.meaning_en || '',
      meaning_ar: entry.meaning_ar || '',
      example: entry.example || '',
      contexts: entry.context ? [entry.context] : [],
      source: entry.source || { type: 'manual' },
      createdAt: Date.now(),
      lastAskedAt: Date.now(),
      asks: 1,
      srs: newSrs(),
      history: [],
    };
    return { ...s, words: [w, ...s.words] };
  });
  return { id, isNew };
}

export function editWord(id, patch) {
  update((s) => ({ ...s, words: s.words.map((w) => (w.id === id ? { ...w, ...patch } : w)) }));
}

export function deleteWord(id) {
  update((s) => ({ ...s, words: s.words.filter((w) => w.id !== id) }));
}

export function restoreWord(word) {
  update((s) => (s.words.some((w) => w.id === word.id) ? s : { ...s, words: [word, ...s.words] }));
}

export function gradeWord(id, quality, via) {
  update((s) => ({
    ...s,
    words: s.words.map((w) =>
      w.id === id
        ? { ...w, srs: review(w.srs, quality), history: [...(w.history || []), { t: Date.now(), q: quality, via }].slice(-30) }
        : w
    ),
  }));
}

export function findWord(text) {
  const k = keyOf(text);
  return state.words.find((w) => keyOf(w.word) === k || keyOf(w.lemma) === k);
}

export function setProgress(epId, patch) {
  update((s) => ({ ...s, progress: { ...s.progress, [epId]: { ...(s.progress[epId] || {}), ...patch, updatedAt: Date.now() } } }));
}

export function setSettings(patch) {
  update((s) => ({ ...s, settings: { ...s.settings, ...patch } }));
}

export function setProfile(patch) {
  update((s) => ({ ...s, profile: { ...(s.profile || {}), ...patch } }));
}

export function addEpisode(ep) {
  const id = 'mine-' + uid();
  update((s) => ({ ...s, customEpisodes: [{ ...ep, id, mine: true, createdAt: Date.now() }, ...s.customEpisodes] }));
  return id;
}

export function removeEpisode(id) {
  update((s) => ({ ...s, customEpisodes: s.customEpisodes.filter((e) => e.id !== id) }));
}

export function resetAll() {
  try { localStorage.removeItem(KEY); } catch {}
  state = structuredClone(defaults);
  listeners.forEach((l) => l());
}
