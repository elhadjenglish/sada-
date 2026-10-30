export function splitSentences(text) {
  return String(text)
    .replace(/\s+/g, ' ')
    .trim()
    .split(/(?<=[.!?…]["”’)]?)\s+(?=["“‘(]?[A-Z0-9])/)
    .map((s) => s.trim())
    .filter(Boolean);
}

// Splits a sentence into tokens; word tokens carry {w} for tapping.
export function tokenize(sentence) {
  const out = [];
  const re = /([A-Za-z][A-Za-z'’-]*)|([^A-Za-z]+)/g;
  let m;
  while ((m = re.exec(sentence))) {
    if (m[1]) out.push({ w: m[1].replace(/’/g, "'") , raw: m[1] });
    else out.push({ raw: m[2] });
  }
  return out;
}

export const norm = (s) =>
  String(s || '')
    .toLowerCase()
    .replace(/[’']/g, "'")
    .replace(/[^a-z0-9' ]+/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();

export function levenshtein(a, b) {
  a = String(a); b = String(b);
  const dp = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = dp[0];
    dp[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = dp[j];
      dp[j] = Math.min(dp[j] + 1, dp[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = tmp;
    }
  }
  return dp[b.length];
}

// Finds a spoken phrase (e.g. the wake phrase) inside recognized text, tolerating small mishearings.
export function matchPhrase(text, phrase) {
  const words = norm(text).split(' ').filter(Boolean);
  const target = norm(phrase).split(' ').filter(Boolean);
  if (!target.length) return null;
  for (let i = 0; i + target.length <= words.length; i++) {
    const chunk = words.slice(i, i + target.length).join(' ');
    const t = target.join(' ');
    if (levenshtein(chunk, t) <= Math.max(1, Math.floor(t.length * 0.3))) {
      return { rest: words.slice(i + target.length).join(' ') };
    }
  }
  return null;
}

export function shuffle(arr) {
  const a = [...arr];
  for (let i = a.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [a[i], a[j]] = [a[j], a[i]];
  }
  return a;
}

export const hasArabic = (s) => /[\u0600-\u06FF]/.test(String(s || ''));
