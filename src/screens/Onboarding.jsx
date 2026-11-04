import React, { useState } from 'react';
import { setProfile } from '../lib/store.js';
import { LEVELS } from '../lib/episodes.js';
import { Rings, Ar, Icon } from '../ui.jsx';

// The podcasts stay English; this is the language Sada explains words and answers questions in.
const LANGS = [
  { id: 'en', flag: '🇬🇧', title: 'English', note: 'Simple English' },
  { id: 'ar', flag: '🇸🇦', title: 'العربية', note: 'Arabic' },
  { id: 'fr', flag: '🇫🇷', title: 'Français', note: 'French' },
  { id: 'es', flag: '🇪🇸', title: 'Español', note: 'Spanish' },
  { id: 'hi', flag: '🇮🇳', title: 'हिन्दी', note: 'Hindi' },
  { id: 'pt', flag: '🇧🇷', title: 'Português', note: 'Portuguese' },
  { id: 'de', flag: '🇩🇪', title: 'Deutsch', note: 'German' },
  { id: 'zh', flag: '🇨🇳', title: '中文', note: 'Chinese' },
];

export default function Onboarding() {
  const [step, setStep] = useState(0);
  const [name, setName] = useState('');
  const [lang, setLang] = useState('en');
  const [level, setLevel] = useState('B1');

  const finish = () => {
    setProfile({ name: name.trim(), explainLang: lang, level, done: true, createdAt: Date.now() });
  };

  return (
    <div className="onb">
      {step > 0 && (
        <div className="onb-top">
          <button className="icon-btn" onClick={() => setStep(step - 1)} aria-label="Back"><Icon name="back" /></button>
          <div className="dots" aria-label={`Step ${step} of 3`}>
            {[1, 2, 3].map((i) => <span key={i} className={i <= step ? 'on' : ''} />)}
          </div>
          <span style={{ width: 40 }} />
        </div>
      )}

      {step === 0 && (
        <section className="onb-hero">
          <Rings size={200} word="helpful" />
          <h1 className="display">Every word you catch<br />becomes yours.</h1>
          <p className="lede">Listen to real English. When a word slips past you, tap it or just ask. Sada explains it, keeps it, and brings it back until it sticks.</p>
          <button className="btn primary big" onClick={() => setStep(1)}>Get started</button>
          <p className="fine">Your words stay on this device. No account needed to start.</p>
        </section>
      )}

      {step === 1 && (
        <section className="onb-step">
          <h2 className="h2">What should we call you?</h2>
          <input className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Your first name" autoComplete="given-name" maxLength={30} />
          <h2 className="h2 mt">In which language should Sada explain words and answer questions?</h2>
          <div className="choices">
            {LANGS.map((l) => (
              <button key={l.id} className={`choice ${lang === l.id ? 'on' : ''}`} onClick={() => setLang(l.id)} aria-pressed={lang === l.id}>
                <span className="choice-title">{l.flag} {l.title}</span>
                <span className="choice-note">{l.note}</span>
              </button>
            ))}
          </div>
          <button className="btn primary big" onClick={() => setStep(2)}>Continue</button>
        </section>
      )}

      {step === 2 && (
        <section className="onb-step">
          <h2 className="h2">Which sounds most like you?</h2>
          <p className="muted">This sets the difficulty of episodes and exercises. You can change it later.</p>
          <div className="choices">
            {LEVELS.map((l) => (
              <button key={l.id} className={`choice row ${level === l.id ? 'on' : ''}`} onClick={() => setLevel(l.id)} aria-pressed={level === l.id}>
                <span className={`level lv-${l.id}`}>{l.id}</span>
                <span><span className="choice-title">{l.can}</span><span className="choice-note">{l.name}</span></span>
              </button>
            ))}
          </div>
          <button className="btn primary big" onClick={() => setStep(3)}>Continue</button>
        </section>
      )}

      {step === 3 && (
        <section className="onb-step">
          <h2 className="h2">Ask with your voice</h2>
          <p className="muted">While an episode plays, tap the mic and ask, for example: <em>“What does helpful mean?”</em> The episode pauses, Muse Voice Transcribe hears your question, and the episode carries on when you’re ready.</p>
          <div className="card soft">
            <p className="label">Hands-free</p>
            <p className="fine">With your phone in your pocket, double-press the <b>Volume Up (+)</b> button to pause the episode and ask. After the answer, say “Continue” or double-press Volume Up again to keep listening.</p>
          </div>
          <button className="btn primary big" onClick={finish}>Start learning</button>
        </section>
      )}
    </div>
  );
}
