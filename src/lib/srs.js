// Spaced repetition: a small SM-2 variant. quality: 1 = forgot, 3 = hard, 4 = good, 5 = easy.
const DAY = 86400000;

export function newSrs() {
  return { ease: 2.5, interval: 0, reps: 0, lapses: 0, due: Date.now() };
}

export function review(srs, quality, now = Date.now()) {
  let { ease, interval, reps, lapses } = srs;
  if (quality < 3) {
    reps = 0;
    lapses += 1;
    interval = 0;
    ease = Math.max(1.3, ease - 0.2);
    return { ease, interval, reps, lapses, due: now + 10 * 60 * 1000 };
  }
  reps += 1;
  interval = reps === 1 ? 1 : reps === 2 ? 3 : Math.round(interval * ease);
  ease = Math.max(1.3, ease + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
  return { ease, interval, reps, lapses, due: now + interval * DAY };
}

export function statusOf(w, now = Date.now()) {
  const s = w.srs;
  if (s.reps === 0 && s.lapses === 0) return 'new';
  if (s.interval >= 21 && s.due > now) return 'mastered';
  if (s.due <= now) return 'review';
  return 'learning';
}

export const isStruggling = (w) => (w.srs?.lapses || 0) >= 2 || (w.asks || 1) >= 2;
export const isDue = (w, now = Date.now()) => w.srs.due <= now;

// Priority for personalized practice: due, struggling, new, then recently caught.
export function pickTargets(words, n = 6, now = Date.now()) {
  return [...words]
    .map((w) => {
      let score = 0;
      if (isDue(w, now)) score += 3;
      if (isStruggling(w)) score += 3;
      if (statusOf(w, now) === 'new') score += 2;
      if (statusOf(w, now) === 'mastered') score -= 4;
      score += Math.max(0, 2 - (now - (w.lastAskedAt || w.createdAt)) / (3 * DAY));
      return { w, score };
    })
    .sort((a, b) => b.score - a.score)
    .slice(0, n)
    .map((x) => x.w);
}

export const STATUS_LABEL = { new: 'New', learning: 'Learning', review: 'Needs review', mastered: 'Mastered' };
