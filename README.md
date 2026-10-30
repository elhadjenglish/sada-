# Sada (صدى)

**Every word you catch becomes yours.**

Sada is an English-listening companion for Arabic-speaking learners. You listen to an episode; when a word slips past you, you tap it or ask out loud. Sada pauses, explains the word *in the sentence you just heard* (in Arabic, simple English, or both), saves it with that sentence, and then builds your reviews and exercises around exactly those words until they stick.

The name means *echo*: the words you catch keep coming back to you.

## What works in Stage 1

| Feature | How it works | Meta model |
|---|---|---|
| Tap any word to ask | Explanation tuned to the sentence and your CEFR level, in Arabic, simple English, or both | Muse Spark 1.3 (`/api/ai`, task `explain`) |
| Ask with your voice | Tap the mic, ask, tap send. A 16 kHz WAV clip is transcribed, biased toward the words in the current sentence | Muse Voice Transcribe (`/api/transcribe`) → Muse Spark |
| “Continue” | Saying “continue” resumes the episode | Muse Voice Transcribe |
| Hands-free wake phrase (experimental) | Configurable phrase (default “Hey Sada”) using Chrome’s built-in recognizer while the app is open | Browser only; the question itself is answered by Muse Spark |
| Vocabulary memory | Word, Arabic + English meaning, example, every sentence you heard it in, source, date, times asked, status | — |
| Struggle detection | Asking about a saved word again, or failing it twice, marks it as struggling and brings it back sooner | — |
| Spaced repetition | SM-2 variant; statuses New, Learning, Needs review, Mastered | — |
| Quick review on open | Up to 5 due words on the Today screen, with “Skip for today”. Never blocks the app | — |
| Personal sessions | Exercises written around your due/struggling/recent words, in new contexts: meaning, context, gap-fill, sentence building, translation, explain-in-your-own-words, say-it-aloud | Muse Spark (`exercises`) |
| Meaning-based grading | Open answers are judged by meaning, not exact string match | Muse Spark (`grade`) |
| Pronunciation check | Say the word; Sada compares what was heard (no keyword biasing, so mispronunciations aren’t hidden) | Muse Voice Transcribe |
| Offline drill | Multiple-choice and gap-fill built locally from your own words when there’s no connection | — |
| Your own episodes | Paste a transcript you have rights to, optionally with a link to the publisher’s audio file | — |

### Honest limits

- **Episodes** are six original scripts written for Sada (A2 to B2), narrated by the phone’s English voice one sentence at a time, so the current sentence is always highlighted. No third-party podcast audio is downloaded or redistributed.
- **Publisher audio** (your own episodes with an audio link) plays without sentence sync; Muse Voice Transcribe returns turn-level, not word-level, timestamps, so Sada estimates the current sentence from the playback position.
- **Hands-free** only works while Sada is open, in Chrome-based browsers. Background listening and controlling other podcast apps are not implemented.
- **Storage is on this device only** (localStorage). There is no account and no sync between devices yet. `supabase/schema.sql` and `docs/DATABASE_PLAN.md` describe how sync will be added without losing local words.
- **Meta integration has only been tested against a mock.** See `TESTING.md` for the live checks to run after deploying.
- **Muse Image** (weekly wallpaper) is Stage 2 and not called anywhere yet.

## Run it

Requires a Meta Model API key (dev.meta.ai). The key lives only on the server.

1. Push this folder to a GitHub repo.
2. Import the repo in Vercel. It detects Vite automatically; the `api/` folder becomes serverless functions.
3. In Vercel → Settings → Environment Variables add `MODEL_API_KEY`. See `.env.example` for optional settings (`META_MODEL` defaults to `muse-spark-1.3`).
4. Recommended: add Upstash Redis from the Vercel Marketplace so rate limits are shared across server instances.
5. Redeploy, open the site, and work through `TESTING.md`, starting with Settings → **Test connection**.

## Security

- The Meta key is only read on the server (`api/`). The browser talks to `/api/ai` and `/api/transcribe`, never to Meta.
- Both routes accept only `POST` + JSON from the app's own origin, cap body size, validate every field, and refuse unknown tasks before calling Meta.
- Per-IP limits (per minute and per day), a separate hourly limit for exercise generation, and a whole-app daily ceiling cap spending. With Upstash configured the limits are shared; without it they're per server instance, which is weaker.
- IPs are hashed before being sent to Meta as `safety_identifier`. Logs record Meta's status and error type only, never prompts, answers, audio or keys.
- This is abuse protection, not authentication. Someone determined can still script requests that look like the app; the limits bound what that costs.

Local development with the API routes: `npm install` then `npx vercel dev`.

## Architecture

```
src/
  App.jsx                 shell + tab bar
  screens/                Onboarding, Today, Listen (library + player + ask sheet), Words, Practice, Settings
  lib/store.js            device store, vocabulary operations, activity + streak
  lib/srs.js              spaced repetition + target selection for practice
  lib/recorder.js         mic → mono 16-bit 16 kHz WAV (format Muse Voice Transcribe accepts)
  lib/speech.js           narration voice + optional wake-phrase listener
  lib/ai.js               browser client for /api routes (never sees the key)
  lib/episodes.js         original episodes + CEFR levels
api/
  _meta.js                Meta Model API helpers: chat completions with json_schema output, model fallback, safe errors
  _guard.js               origin/method/size checks and rate limits
  ai.js                   tasks: explain, exercises, grade, ping (prompts live on the server)
  transcribe.js           forwards WAV to /v1/asr/transcribe in PUSH_TO_TALK mode with language + keyword biasing
supabase/schema.sql       tables + RLS for future sync (not connected)
docs/DATABASE_PLAN.md     how sync gets added safely
tests/server.test.mjs     mocked server tests (npm test)
```

The AI layer returns JSON, which the client validates before use: malformed exercises are dropped, MCQ options are reshuffled, and a session needs at least two valid exercises or it says so and offers the offline drill.

## Roadmap

**Stage 2:** account sync (per `docs/DATABASE_PLAN.md`), weekly vocabulary wallpaper (Muse Image background + words rendered by code for exact spelling), learning paths per level, analytics, recommendations.
**Stage 3:** profiles with opt-in public words, language-exchange partners, voice rooms.

## Built during the Meta hackathon

All code in this repository, the episode scripts, and the design were created during the hackathon.
