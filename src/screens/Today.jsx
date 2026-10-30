import React, { useMemo, useState } from 'react';
import { useStore, update, todayKey, streakOf, gradeWord, logActivity } from '../lib/store.js';
import { statusOf, isDue, isStruggling } from '../lib/srs.js';
import { EPISODES, levelDistance } from '../lib/episodes.js';
import { Icon, Ar, LevelTag } from '../ui.jsx';
import { speakOnce } from '../lib/speech.js';

function greeting() {
  const h = new Date().getHours();
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening';
}

export default function Today({ go, openSettings }) {
  const s = useStore();
  const { profile, words, activity, progress } = s;
  const now = Date.now();
  const due = useMemo(() => words.filter((w) => isDue(w, now)).slice(0, 5), [words]); // eslint-disable-line
  const showReview = due.length > 0 && s.reviewSkippedOn !== todayKey();
  const streak = streakOf(activity);
  const allEps = [...s.customEpisodes, ...EPISODES];

  const lastEp = Object.entries(progress)
    .filter(([id, p]) => !p.done && allEps.some((e) => e.id === id))
    .sort((a, b) => b[1].updatedAt - a[1].updatedAt)[0];
  const continueEp = lastEp && allEps.find((e) => e.id === lastEp[0]);
  const suggestion = EPISODES
    .filter((e) => !progress[e.id]?.done && e.id !== continueEp?.id)
    .sort((a, b) => levelDistance(a.level, profile.level) - levelDistance(b.level, profile.level))[0];

  const counts = words.reduce((acc, w) => { acc[statusOf(w, now)]++; return acc; }, { new: 0, learning: 0, review: 0, mastered: 0 });
  const struggling = words.filter(isStruggling).slice(0, 6);

  return (
    <div className="screen">
      <header className="top">
        <div>
          <p className="muted small">{new Date().toLocaleDateString('en-GB', { weekday: 'long', day: 'numeric', month: 'long' })}</p>
          <h1 className="h1">{greeting()}{profile.name ? `, ${profile.name}` : ''}</h1>
        </div>
        <button className="icon-btn" onClick={openSettings} aria-label="Settings"><Icon name="settings" /></button>
      </header>

      <WeekStrip activity={activity} streak={streak} />

      {showReview && <QuickReview words={due} lang={profile.explainLang} />}

      {continueEp ? (
        <button className="card ep-card hero-ep" onClick={() => go('listen', { episodeId: continueEp.id })}>
          <span className="muted small">Continue listening</span>
          <span className="ep-title">{continueEp.title}</span>
          <ProgressBar value={(lastEp[1].idx || 0) / Math.max(1, lastEp[1].total || 1)} />
        </button>
      ) : suggestion ? (
        <button className="card ep-card hero-ep" onClick={() => go('listen', { episodeId: suggestion.id })}>
          <span className="muted small">Start here · {suggestion.minutes} min</span>
          <span className="ep-title">{suggestion.title}</span>
          <span className="ep-blurb">{suggestion.blurb}</span>
          <span className="row gap"><LevelTag level={suggestion.level} /><span className="btn-inline"><Icon name="play" size={16} fill /> Listen</span></span>
        </button>
      ) : null}

      <section className="block">
        <div className="row between">
          <h2 className="h3">Your words</h2>
          {words.length > 0 && <button className="link" onClick={() => go('words')}>See all</button>}
        </div>
        {words.length === 0 ? (
          <p className="muted">Nothing caught yet. Play an episode and tap any word you don’t know; it lands here.</p>
        ) : (
          <>
            <div className="statline">
              <Stat n={counts.new} label="new" tone="plum" />
              <Stat n={counts.learning} label="learning" tone="ink" />
              <Stat n={counts.review} label="to review" tone="sun" />
              <Stat n={counts.mastered} label="mastered" tone="mint" />
            </div>
            {struggling.length > 0 && (
              <div className="struggle">
                <p className="small muted">Keep coming back to you</p>
                <div className="chips">
                  {struggling.map((w) => <span key={w.id} className="chip caught">{w.word}</span>)}
                </div>
                <button className="btn secondary" onClick={() => go('practice')}>Practice these</button>
              </div>
            )}
          </>
        )}
      </section>
    </div>
  );
}

function Stat({ n, label, tone }) {
  return <div className={`stat t-${tone}`}><b>{n}</b><span>{label}</span></div>;
}

export function ProgressBar({ value }) {
  return <span className="pbar" role="progressbar" aria-valuenow={Math.round(value * 100)} aria-valuemin={0} aria-valuemax={100}><span style={{ width: `${Math.min(100, value * 100)}%` }} /></span>;
}

function WeekStrip({ activity, streak }) {
  const days = [...Array(7)].map((_, i) => {
    const d = new Date();
    d.setDate(d.getDate() - (6 - i));
    return { k: todayKey(d), label: d.toLocaleDateString('en-GB', { weekday: 'narrow' }), today: i === 6 };
  });
  return (
    <div className="week">
      <div className="week-days">
        {days.map((d) => (
          <span key={d.k} className={`wd ${activity[d.k] ? 'on' : ''} ${d.today ? 'today' : ''}`}>
            <i aria-hidden="true" />{d.label}
          </span>
        ))}
      </div>
      <span className="streak"><Icon name="flame" size={18} fill />{streak} day{streak === 1 ? '' : 's'}</span>
    </div>
  );
}

function QuickReview({ words, lang }) {
  const [i, setI] = useState(0);
  const [shown, setShown] = useState(false);
  const [done, setDone] = useState(0);
  const w = words[i];

  const answer = (q) => {
    gradeWord(w.id, q, 'quick-review');
    logActivity('reviewed');
    setDone(done + 1);
    setShown(false);
    setI(i + 1);
  };
  const skip = () => update((s) => ({ ...s, reviewSkippedOn: todayKey() }));

  if (!w) {
    return (
      <section className="card review done">
        <p className="h3">Review done. {done} word{done === 1 ? '' : 's'} refreshed.</p>
        <p className="muted small">They’ll come back right before you’d forget them.</p>
      </section>
    );
  }
  const ctx = w.contexts?.[0]?.sentence;
  return (
    <section className="card review" aria-label="Quick review">
      <div className="row between">
        <span className="small muted">Quick review · {i + 1} of {words.length}</span>
        <button className="link" onClick={skip}>Skip for today</button>
      </div>
      <button className="review-word" onClick={() => speakOnce(w.word)} aria-label={`Hear ${w.word}`}>
        {w.word} <Icon name="speaker" size={18} />
      </button>
      {ctx && <p className="ctx">“{highlight(ctx, w.word)}”</p>}
      {shown ? (
        <>
          <div className="meaning">
            {lang !== 'en' && w.meaning_ar && <Ar className="big-ar">{w.meaning_ar}</Ar>}
            {lang !== 'ar' && w.meaning_en && <p>{w.meaning_en}</p>}
            {lang === 'ar' && !w.meaning_ar && <p>{w.meaning_en}</p>}
          </div>
          <div className="row gap">
            <button className="btn ghost grow" onClick={() => answer(1)}>I forgot</button>
            <button className="btn primary grow" onClick={() => answer(4)}>I knew it</button>
          </div>
        </>
      ) : (
        <button className="btn secondary wide" onClick={() => setShown(true)}>Show meaning</button>
      )}
    </section>
  );
}

export function highlight(sentence, word) {
  const re = new RegExp(`\\b(${word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\w*)`, 'i');
  const parts = sentence.split(re);
  return parts.map((p, i) => (i % 2 === 1 ? <mark key={i} className="mk">{p}</mark> : p));
}
