import React, { useState } from 'react';
import { useStore, setSettings, setProfile, resetAll, storageWorks } from '../lib/store.js';
import { LEVELS } from '../lib/episodes.js';
import { pingAI } from '../lib/ai.js';
import { Icon, Spinner } from '../ui.jsx';

export default function Settings({ onClose }) {
  const s = useStore();
  const { profile, settings } = s;
  const [check, setCheck] = useState(null);

  const test = async () => {
    setCheck({ busy: true });
    try {
      const data = await pingAI();
      const used = data.model || 'unknown model';
      const note = data.configuredModel && data.configuredModel !== used ? ` (${data.configuredModel} was not available, so Sada fell back)` : '';
      setCheck({ ok: `Meta answered using ${used}${note}.` });
    } catch (e) {
      setCheck({ err: e.message });
    }
  };

  const exportWords = () => {
    const blob = new Blob([JSON.stringify(s.words, null, 2)], { type: 'application/json' });
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `sada-words-${new Date().toISOString().slice(0, 10)}.json`;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 2000);
  };

  return (
    <div className="screen">
      <header className="top">
        <button className="icon-btn" onClick={onClose} aria-label="Back"><Icon name="back" /></button>
        <h1 className="h1 grow">Settings</h1>
      </header>

      <section className="set-group">
        <label className="label" htmlFor="s-name">Name</label>
        <input id="s-name" className="input" value={profile.name || ''} onChange={(e) => setProfile({ name: e.target.value })} maxLength={30} />

        <label className="label" htmlFor="s-level">Level</label>
        <select id="s-level" className="input" value={profile.level} onChange={(e) => setProfile({ level: e.target.value })}>
          {LEVELS.map((l) => <option key={l.id} value={l.id}>{l.id} · {l.name}</option>)}
        </select>

        <label className="label" htmlFor="s-lang">Explanations</label>
        <select id="s-lang" className="input" value={profile.explainLang} onChange={(e) => setProfile({ explainLang: e.target.value })}>
          <option value="ar">Arabic</option>
          <option value="en">Simple English</option>
          <option value="both">Arabic and simple English</option>
        </select>
      </section>

      <section className="set-group">
        <label className="label" htmlFor="s-wake">Hands-free phrase</label>
        <input id="s-wake" className="input" value={settings.wakePhrase} onChange={(e) => setSettings({ wakePhrase: e.target.value })} maxLength={30} />
        <p className="fine">Two or three distinct words work best. Hands-free uses Chrome’s built-in recognizer while Sada is open; questions you ask with the mic button go to Muse Voice Transcribe.</p>

        <label className="toggle">
          <input type="checkbox" checked={settings.autoSave} onChange={(e) => setSettings({ autoSave: e.target.checked })} />
          <span>Save words automatically when I ask about them</span>
        </label>
      </section>

      <section className="set-group">
        <p className="label">AI connection</p>
        <button className="btn secondary" onClick={test} disabled={check?.busy}>{check?.busy ? <Spinner /> : 'Test connection'}</button>
        {check?.ok && <p className="t-mint small">{check.ok}</p>}
        {check?.err && <p className="error small">{check.err}</p>}
      </section>

      <section className="set-group">
        <p className="label">Your data</p>
        <p className="fine">Words and progress are stored on this device{storageWorks() ? '' : ' (storage is blocked in this browser, so they will be lost when you close it)'}.</p>
        <div className="row gap">
          <button className="btn secondary grow" onClick={exportWords} disabled={!s.words.length}>Export words</button>
          <button className="btn ghost grow danger-text" onClick={() => { if (confirm('Delete all words, progress and settings on this device?')) resetAll(); }}>Delete everything</button>
        </div>
      </section>
    </div>
  );
}
