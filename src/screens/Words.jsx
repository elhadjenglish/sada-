import React, { useMemo, useState } from 'react';
import { useStore, editWord, deleteWord, restoreWord } from '../lib/store.js';
import { statusOf, isStruggling, STATUS_LABEL } from '../lib/srs.js';
import { norm } from '../lib/text.js';
import { speakOnce } from '../lib/speech.js';
import { Icon, Sheet, Ar, toast } from '../ui.jsx';
import { highlight } from './Today.jsx';

const FILTERS = ['all', 'new', 'learning', 'review', 'mastered', 'struggling'];
const FILTER_LABEL = { all: 'All', struggling: 'Struggling', ...STATUS_LABEL };

export default function Words({ go }) {
  const s = useStore();
  const [f, setF] = useState('all');
  const [q, setQ] = useState('');
  const [openId, setOpenId] = useState(null);
  const lang = s.profile.explainLang;

  const list = useMemo(() => {
    const query = norm(q);
    return s.words.filter((w) => {
      if (f === 'struggling' ? !isStruggling(w) : f !== 'all' && statusOf(w) !== f) return false;
      return !query || norm(`${w.word} ${w.meaning_en}`).includes(query) || (w.meaning_ar || '').includes(q.trim());
    });
  }, [s.words, f, q]);

  const open = s.words.find((w) => w.id === openId);

  if (s.words.length === 0) {
    return (
      <div className="screen">
        <header className="top"><h1 className="h1">Words</h1></header>
        <div className="empty-state">
          <p className="h3">Your collection starts in your ears.</p>
          <p className="muted">Open an episode and tap a word you don’t know. Its meaning, the sentence you heard it in, and where it came from all land here.</p>
          <button className="btn primary" onClick={() => go('listen')}>Open an episode</button>
        </div>
      </div>
    );
  }

  return (
    <div className="screen">
      <header className="top"><h1 className="h1">Words <span className="count">{s.words.length}</span></h1></header>
      <label className="search">
        <Icon name="search" size={18} />
        <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search your words" aria-label="Search your words" />
      </label>
      <div className="chips scroll" role="group" aria-label="Filter words">
        {FILTERS.map((k) => (
          <button key={k} className={`chip ${f === k ? 'on' : ''}`} onClick={() => setF(k)} aria-pressed={f === k}>{FILTER_LABEL[k]}</button>
        ))}
      </div>
      {list.length === 0 ? (
        <p className="muted empty">No words here yet.</p>
      ) : (
        <ul className="word-list">
          {list.map((w) => {
            const st = statusOf(w);
            return (
              <li key={w.id}>
                <button className="word-row" onClick={() => setOpenId(w.id)}>
                  <span className={`dot st-${st}`} aria-hidden="true" />
                  <span className="wr-main">
                    <span className="wr-word">{w.word}{isStruggling(w) && <span className="flag">asked {w.asks}×</span>}</span>
                    <span className="wr-mean">
                      {lang !== 'en' && w.meaning_ar ? <Ar>{w.meaning_ar}</Ar> : w.meaning_en}
                    </span>
                  </span>
                  <span className="small muted">{STATUS_LABEL[st]}</span>
                </button>
              </li>
            );
          })}
        </ul>
      )}
      <WordDetail word={open} onClose={() => setOpenId(null)} />
    </div>
  );
}

function WordDetail({ word, onClose }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState({});
  if (!word) return <Sheet open={false} />;

  const startEdit = () => { setDraft({ word: word.word, meaning_ar: word.meaning_ar, meaning_en: word.meaning_en, example: word.example }); setEditing(true); };
  const saveEdit = () => {
    if (!draft.word.trim()) return toast('The word can’t be empty.');
    editWord(word.id, { ...draft, word: draft.word.trim() });
    setEditing(false);
  };
  const remove = () => {
    const copy = word;
    deleteWord(word.id);
    onClose();
    toast(`Deleted “${copy.word}”.`, { label: 'Undo', run: () => restoreWord(copy) });
  };
  const st = statusOf(word);
  const nextDue = new Date(word.srs.due);

  return (
    <Sheet open onClose={() => { setEditing(false); onClose(); }} label={`Word: ${word.word}`} tall>
      {editing ? (
        <div className="edit">
          <label className="label" htmlFor="ew">Word</label>
          <input id="ew" className="input" value={draft.word} onChange={(e) => setDraft({ ...draft, word: e.target.value })} />
          <label className="label" htmlFor="ea">Arabic meaning</label>
          <input id="ea" className="input" dir="rtl" lang="ar" value={draft.meaning_ar} onChange={(e) => setDraft({ ...draft, meaning_ar: e.target.value })} />
          <label className="label" htmlFor="ee">English meaning</label>
          <input id="ee" className="input" value={draft.meaning_en} onChange={(e) => setDraft({ ...draft, meaning_en: e.target.value })} />
          <label className="label" htmlFor="ex">Example</label>
          <textarea id="ex" className="input area sm" value={draft.example} onChange={(e) => setDraft({ ...draft, example: e.target.value })} />
          <div className="row gap"><button className="btn ghost grow" onClick={() => setEditing(false)}>Cancel</button><button className="btn primary grow" onClick={saveEdit}>Save changes</button></div>
        </div>
      ) : (
        <div className="detail">
          <div className="ask-head">
            <button className="ask-word" onClick={() => speakOnce(word.lemma || word.word)} aria-label={`Hear ${word.word}`}>{word.word} <Icon name="speaker" size={20} /></button>
            <span className="muted small">{[word.pos, word.ipa && `/${word.ipa.replace(/^\/|\/$/g, '')}/`].filter(Boolean).join('  ')}</span>
          </div>
          {word.meaning_ar && <Ar className="big-ar">{word.meaning_ar}</Ar>}
          {word.meaning_en && <p className="def">{word.meaning_en}</p>}
          {word.example && <p className="example">“{word.example}”</p>}

          {word.contexts?.length > 0 && (
            <div className="where">
              <p className="small muted">Where you heard it</p>
              {word.contexts.map((c, i) => (
                <blockquote key={i}>“{highlight(c.sentence, word.word)}”<cite>{c.title}</cite></blockquote>
              ))}
            </div>
          )}

          <dl className="facts">
            <div><dt>Status</dt><dd className={`st-txt st-${st}`}>{STATUS_LABEL[st]}</dd></div>
            <div><dt>Caught</dt><dd>{new Date(word.createdAt).toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}</dd></div>
            <div><dt>Asked</dt><dd>{word.asks}×</dd></div>
            <div><dt>Next review</dt><dd>{nextDue <= new Date() ? 'Now' : nextDue.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })}</dd></div>
          </dl>

          <div className="row gap">
            <button className="btn ghost" onClick={remove}><Icon name="trash" size={18} /> Delete</button>
            <button className="btn secondary grow" onClick={startEdit}>Edit</button>
          </div>
        </div>
      )}
    </Sheet>
  );
}
