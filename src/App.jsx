import React, { useState } from 'react';
import { useStore } from './lib/store.js';
import { Icon, Toaster, DemoBanner } from './ui.jsx';
import Onboarding from './screens/Onboarding.jsx';
import Today from './screens/Today.jsx';
import Listen from './screens/Listen.jsx';
import Words from './screens/Words.jsx';
import Practice from './screens/Practice.jsx';
import Settings from './screens/Settings.jsx';

const TABS = [
  { id: 'today', label: 'Today', icon: 'today' },
  { id: 'listen', label: 'Listen', icon: 'listen' },
  { id: 'words', label: 'Words', icon: 'words' },
  { id: 'practice', label: 'Practice', icon: 'practice' },
];

export default function App() {
  const s = useStore();
  const [tab, setTab] = useState('today');
  const [episodeId, setEpisodeId] = useState(null);
  const [settingsOpen, setSettingsOpen] = useState(false);

  if (!s.profile?.done) return <><DemoBanner /><Onboarding /><Toaster /></>;

  const go = (t, opts = {}) => {
    setSettingsOpen(false);
    setTab(t);
    if (t === 'listen') setEpisodeId(opts.episodeId ?? null);
    window.scrollTo(0, 0);
  };

  const inPlayer = tab === 'listen' && episodeId;

  return (
    <div className="app">
      <DemoBanner />
      <main className={`main ${inPlayer ? 'player-mode' : ''}`}>
        {settingsOpen ? (
          <Settings onClose={() => setSettingsOpen(false)} />
        ) : (
          <>
            {tab === 'today' && <Today go={go} openSettings={() => setSettingsOpen(true)} />}
            {tab === 'listen' && <Listen episodeId={episodeId} setEpisodeId={setEpisodeId} go={go} />}
            {tab === 'words' && <Words go={go} />}
            {tab === 'practice' && <Practice go={go} />}
          </>
        )}
      </main>
      {!inPlayer && (
        <nav className="tabbar" aria-label="Main">
          {TABS.map((t) => (
            <button key={t.id} className={`tab ${tab === t.id && !settingsOpen ? 'on' : ''}`} onClick={() => go(t.id)}
              aria-current={tab === t.id && !settingsOpen ? 'page' : undefined}>
              <Icon name={t.icon} />
              <span>{t.label}</span>
            </button>
          ))}
        </nav>
      )}
      <Toaster />
    </div>
  );
}
