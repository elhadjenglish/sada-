import React, { useEffect, useState } from 'react';
import { getState } from './lib/store.js';

const P = {
  today: 'M12 3v2M12 19v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M3 12h2M19 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8z',
  listen: 'M4 14v-2a8 8 0 0 1 16 0v2M4 14h3v6H5a1 1 0 0 1-1-1v-5zM20 14h-3v6h2a1 1 0 0 0 1-1v-5z',
  words: 'M5 4h10a4 4 0 0 1 4 4v12H9a4 4 0 0 1-4-4V4zM9 9h6M9 13h4',
  practice: 'M12 3l2.4 5.6L20 9.3l-4.3 3.9 1.2 5.8L12 16l-4.9 3 1.2-5.8L4 9.3l5.6-.7L12 3z',
  mic: 'M12 3a3 3 0 0 0-3 3v6a3 3 0 0 0 6 0V6a3 3 0 0 0-3-3zM5 11a7 7 0 0 0 14 0M12 18v3',
  play: 'M7 4.5v15l13-7.5-13-7.5z',
  pause: 'M7 4h3.5v16H7zM13.5 4H17v16h-3.5z',
  back: 'M15 5l-7 7 7 7',
  next: 'M5 5l8 7-8 7V5zM17 5v14',
  prev: 'M19 5l-8 7 8 7V5zM7 5v14',
  settings: 'M12 9a3 3 0 1 0 0 6 3 3 0 0 0 0-6zM19.4 15a1.6 1.6 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.6 1.6 0 0 0-2.7 1.1V21a2 2 0 1 1-4 0v-.1a1.6 1.6 0 0 0-2.7-1.1l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1A1.6 1.6 0 0 0 3.5 14.3H3a2 2 0 1 1 0-4h.1a1.6 1.6 0 0 0 1.1-2.7l-.1-.1A2 2 0 1 1 6.9 4.7l.1.1a1.6 1.6 0 0 0 1.8.3H9a1.6 1.6 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.6 1.6 0 0 0 1 1.5 1.6 1.6 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.6 1.6 0 0 0-.3 1.8V9a1.6 1.6 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.6 1.6 0 0 0-1.5 1z',
  close: 'M6 6l12 12M18 6L6 18',
  check: 'M5 12.5l4.5 4.5L19 7.5',
  ear: 'M7 9a5 5 0 0 1 10 0c0 3-3 4-3 7a3 3 0 0 1-6 0M10 9a2 2 0 0 1 4 0',
  speaker: 'M4 9h4l5-4v14l-5-4H4V9zM16.5 8.5a5 5 0 0 1 0 7',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zM20 20l-4-4',
  trash: 'M4 7h16M9 7V4h6v3M6 7l1 13h10l1-13',
  plus: 'M12 5v14M5 12h14',
  flame: 'M12 3c1 4 5 5.5 5 10a5 5 0 0 1-10 0c0-2 1-3.5 2-4.5.5 2 1.5 3 2.5 3-.5-3 0-6 .5-8.5z',
  send: 'M4 12l16-8-6 16-2.5-6.5L4 12z',
};

export function Icon({ name, size = 22, stroke = 2, fill = false, label }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill={fill ? 'currentColor' : 'none'} stroke={fill ? 'none' : 'currentColor'}
      strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" aria-hidden={label ? undefined : true} role={label ? 'img' : undefined} aria-label={label}>
      <path d={P[name]} />
    </svg>
  );
}

// The brand mark: a caught word sends out rings, like an echo.
export function Rings({ size = 120, word, pulse = true }) {
  return (
    <div className={`rings ${pulse ? 'pulse' : ''}`} style={{ width: size, height: size }} aria-hidden="true">
      <span className="ring r3" /><span className="ring r2" /><span className="ring r1" />
      <span className="core">{word}</span>
    </div>
  );
}

export function Sheet({ open, onClose, children, label, tall }) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e) => e.key === 'Escape' && onClose?.();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);
  if (!open) return null;
  return (
    <div className="sheet-wrap" role="dialog" aria-modal="true" aria-label={label}>
      <button className="sheet-backdrop" aria-label="Close" onClick={onClose} />
      <div className={`sheet ${tall ? 'tall' : ''}`}>
        <div className="sheet-grip" aria-hidden="true" />
        <button className="icon-btn sheet-x" onClick={onClose} aria-label="Close"><Icon name="close" size={20} /></button>
        {children}
      </div>
    </div>
  );
}

// Holds the learner's language text. Arabic (and the Arabic + English mode) read right to left; the other
// explanation languages (French, Spanish, …) are written left to right.
export function Ar({ children, className = '' }) {
  const l = getState().profile?.explainLang;
  const other = !!l && !['ar', 'en', 'both'].includes(l);
  return <span dir={other ? 'ltr' : 'rtl'} lang={other ? l : 'ar'} className={`ar ${className}`}>{children}</span>;
}

// Shows Arabic, English, or both, following the learner's explanation setting.
// If the preferred language is missing, falls back to whichever text exists.
export function Bi({ ar, en, lang }) {
  const showAr = lang !== 'en' && !!ar;
  const showEn = lang !== 'ar' && !!en;
  if (!showAr && !showEn) {
    if (ar) return <Ar className="bi">{ar}</Ar>;
    if (en) return <span className="bi">{en}</span>;
    return null;
  }
  return (
    <>
      {showAr && <Ar className="bi">{ar}</Ar>}
      {showEn && <span className="bi">{en}</span>}
    </>
  );
}

// Only the preview build sets window.__SADA_DEMO__; in the real app these render nothing.
export const DEMO = typeof window !== 'undefined' && !!window.__SADA_DEMO__;
// Shown whenever an answer did not come from Meta (source !== 'meta'), so sample data is never mistaken for AI.
export function DemoTag({ source, children = 'Demo answer · sample data, not from Meta' }) {
  return source && source !== 'meta' ? <span className="demo-tag" role="note">{children}</span> : null;
}
export function DemoBanner() {
  return DEMO ? <div className="demo-banner" role="note">Preview with demo answers. Nothing here comes from Meta AI.</div> : null;
}

let toastFn = null;
export const toast = (msg, action) => toastFn?.(msg, action);

export function Toaster() {
  const [t, setT] = useState(null);
  useEffect(() => {
    toastFn = (msg, action) => setT({ msg, action, id: Date.now() });
    return () => { toastFn = null; };
  }, []);
  useEffect(() => {
    if (!t) return;
    const h = setTimeout(() => setT(null), 4500);
    return () => clearTimeout(h);
  }, [t]);
  if (!t) return null;
  return (
    <div className="toast" role="status" key={t.id}>
      <span>{t.msg}</span>
      {t.action && <button className="link" onClick={() => { t.action.run(); setT(null); }}>{t.action.label}</button>}
    </div>
  );
}

export function Spinner({ label = 'Loading' }) {
  return <span className="spinner" role="status" aria-label={label} />;
}

export function LevelTag({ level }) {
  return <span className={`level lv-${level}`}>{level}</span>;
}
