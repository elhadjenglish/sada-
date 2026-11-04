# Testing

There are two kinds of tests. They prove different things.

## 1. Mocked tests (done, in this repo)

`npm test` runs `tests/server.test.mjs`, `tests/exercises.test.mjs`, `tests/grade.test.mjs`, `tests/handsfree.test.mjs` and `tests/wake.test.mjs`: **98 tests** (November 2026 review). The Android logic tests (Java, simulated audio) run in GitHub Actions as part of the APK build: **102 tests**. The first three run the real `/api/ai` and `/api/transcribe` code against a **local fake of Meta's API**; the hands-free tests drive the real controller with a **simulated** recognizer, player, voice and clock. They cover:

- Chat Completions request shape from Meta's docs: `developer` role, `response_format: json_schema`, `max_completion_tokens`, `reasoning_effort`, default temperature, hashed `safety_identifier`
- Explanation language: `both` returns Arabic and English; `ar` / `en` return only that language
- Fallback from `muse-spark-1.3` to `META_FALLBACK_MODEL` when Meta says the model is unavailable
- Meta errors (400, 401, 429, 500, 504, empty answer, cut-off answer, non-JSON) become safe messages; the key never appears in a response
- A Meta request that never answers ends with a timeout error before Vercel's 60-second limit
- Hands-free (simulated): wake phrase on interim results pauses immediately; one-breath and two-step questions; question ends on silence; no question → resume; custom phrases and small mishearings; podcast speech ignored; only the question sent; one call per question; recognizer off while speaking; echo ignored; follow-up questions; resume at the saved position; AI failure and voice failure; session restarts and network back-off; permission denied; resume while thinking; visibility
- Wake detection with realistic transcripts (simulated): “Good morning” and unrelated sentences don't pause or play Sada's tone; “Hey Sada”, “Hey, Sada.”, “HEY SADA!”, “Hey Sarah”, “hay Sada”, “Heysada”, “hey sa da” pause; a half-heard “hey sa…” doesn't; the phrase split across results or across Android sessions; old fragments don't combine; one-breath and multi-part questions; duplicate finals; Arabic custom phrase
- Open-answer grading: the bug-report example (three valid answers to the “breath” translation all accepted), what the grader is told, minor grammar vs real errors, wrong/partial meaning, missing target word, other translations and explanations, feedback language. These check Sada's prompt and verdict logic; how the real model fills the rubric is only checked live (step 6b below)
- Personal exercises: strict per-type schema; valid, Markdown-wrapped, malformed, cut-off and list-less answers; alternative field names and type spellings; words not in the list; incomplete items retried once then reported as an error; every accepted exercise also passes the app's own validator; logs contain no key or user content
- Input validation, method/origin/content-type/size checks, per-IP and global rate limits
- Transcribe multipart matches Meta's reference (JSON `request` part without filename, WAV `audio` part, `Accept: application/json`); bad WAV files are refused before reaching Meta

### Hands-free in the real UI (`tests/browser/handsfree.browser.mjs`, 27 checks, all pass)

Real Sada screens, real `<audio>` playback and the real `/api` request path, with the **speech recognizer and speech synthesis simulated**. Checks the whole flow: wake → pause → only the question sent → answer spoken (Arabic with an Arabic voice) → recognizer off while speaking → echo ignored → “Continue” → playback restarted at the paused position with no seek (`pause()` and `play()` both at the same time stamp) → narrated episode back on the same sentence; plus session restarts, API failure and microphone denial.

**Not verified anywhere:** a real microphone, real speech recognition accuracy, Bluetooth earphones, the locked screen, Android's start beeps, and real Meta answers in this flow.

### Android-like recognition in the real UI (`tests/browser/android-pattern.browser.mjs`, 15 checks, all pass)

Fake recognizer that ends the session after every utterance, like Android Chrome. Checks: mic button still works; “Good morning” and unrelated sentences don't pause and play no Sada tone; sessions restart; “Hey” and “Sada” in separate sessions pause; question from the next session sent alone; spoken answer; “Continue”; “Hey, Sada, what does reliable mean?”; “Hey Sarah”; the on-screen log; recovery from an error with no “end” event.

### Browser checks (headless Chromium, phone-sized, mocked or demo answers)

| Check | Result |
|---|---|
| Mic on a page inside a frame without `allow="microphone"`, permission granted | Chrome refuses (`NotAllowedError`); Sada now explains it's the hosting page, not the phone |
| Mic in a frame that allows it | Records |
| Mic on a normal page, permission granted (fake microphone) | Records a valid WAV |
| Microphone recording only silence | Reported as "No sound was recorded", nothing sent |
| Mic permission denied | **Not verified**: headless Chrome can't produce a real denial. Message chosen by code review |
| Tap all 606 words in the six episodes (demo) | 0 stuck: 54 sample answers, 552 "No demo answer" notices, nothing saved for those |
| Server never answers (real mode) | Error with Try again after 45 s |
| Narration voice fails | Player stops with a message instead of "playing" silently |
| Real mode shows no demo labels | Confirmed |

The browser flows (onboarding, listening, tap-to-ask, voice recording, saving, editing, deleting, practice, offline drill, errors, reload persistence, light/dark) were run in headless Chromium against **mocked** `/api` responses. The recorder was checked to produce valid mono 16-bit 16 kHz WAV from a fake microphone.

**Not verified anywhere yet:** the microphone on a real Android phone, the stuck-loading report from your phone (it never reproduced here), and anything involving real Meta answers.

**What mocked tests cannot show:** that Meta accepts these exact requests, that your key has access to `muse-spark-1.3`, or how good the real answers and transcripts are.

## 2. Live checks against Meta (to do after deploy)

None of these have been run yet. Do them on the deployed site, on your phone.

| # | Check | Pass when |
|---|---|---|
| 1 | Settings → Test connection | Shows “Meta answered using muse-spark-1.3”. If it names muse-spark-1.1, your account doesn't have 1.3 and Sada fell back. |
| 2 | Open an episode, tap “reliable” with Explanations = Arabic and simple English | Sheet shows Arabic meaning, English definition, and the in-context line in **both** languages |
| 3 | Same with Explanations = Arabic, then Simple English | Only that language in the in-context line |
| 4a | Open the Vercel link **directly in Chrome** (not inside another app), tap the mic | Chrome asks for the microphone, recording starts |
| 4 | Tap the mic, say “What does deep breath mean?”, tap send | Transcript is understood, explanation appears, episode stays paused |
| 5 | Tap the mic, say “continue” | Episode resumes |
| 6 | Practice → Start personal session | Exercises use your saved words; a translation answer gets feedback |
| 6b | In a translation exercise, answer with a different but correct tense or pronoun than the model answer would use (e.g. “I take…” instead of “Take…”) | Marked Correct with positive feedback |
| 7 | Practice → say-it-aloud | Saying the word correctly is marked correct |
| 8 | Vercel → Logs | No `meta 400` lines. If you see `chat:schema-fallback`, Meta rejected the JSON schema and Sada used plain JSON instead; tell me the logged message |
| 9 | Open the site in a second browser and tap ~25 words in a minute | The 21st gets “Too many requests” |

Only after checks 1–7 pass can we say the Meta integration works.

## Reading the exercise diagnostics in Vercel

Every exercise request logs one line per attempt, for example:

```
[ai] op=exercises model=muse-spark-1.3 format=json_schema finish=stop len=2840 attempt=1 parse=ok shape=ok items=6 valid=6 types=mcq:2,fill:1,order:1,translate:1,explain:1 dropped=- missing=- ms=9800
```

- `valid` below 2 on both attempts → the app shows “incomplete twice”. `missing=` names the absent fields (e.g. `mcq.options`), `dropped=` the reason.
- `format=plain` → Meta rejected the JSON schema and Sada fell back to plain JSON.
- `finish=length` → the answer was cut off by the token limit.
- `parse=failed` → the answer was not valid JSON.

Lines contain only counts and fixed field names: no words, sentences, answers, or keys.

## Hands-free on your phone (Chrome, Bluetooth earphones)

Do this on the deployed Vercel site, opened directly in Chrome.

1. Open an episode, tap play, tap the ear button, then tap **Show what Sada hears**.
2. Say **“Good morning”**. Expect: the log shows `Heard “Good morning” → not the wake phrase`, the episode keeps playing. You may still hear a short system sound: that is Android's speech-recognition sound (see below), not Sada's.
3. Say **“Hey Sada”**. Expect: `→ wake phrase ✓`, the episode pauses, Sada's short rising tone.
4. Say **“what does reliable mean”**. Expect: `Question sent: “what does reliable mean”`, then the spoken answer.
5. Say **“Continue”**. Expect: `→ resume phrase ✓`, the episode carries on where it stopped.
6. Say **“Hey Sada, what does reliable mean?”** in one breath, then “Continue”.
7. Turn hands-free off and use the purple mic button once, to confirm it still works.
8. Optional: Bluetooth sound quality, screen locked, and no internet, as before. Screen locked is still expected to fail in the browser.

**If “Hey Sada” still doesn't pause, the log tells us why. Send me the lines it shows:**
- **No “Heard …” lines at all** → Chrome isn't returning any text. Look for `Recognition error: …` lines.
- **Text in Arabic or French** (e.g. `Heard “هاي سادا”`) → Chrome is recognizing in your phone's voice-typing language, not English. Either set the wake phrase in Settings to exactly what it shows, or switch Google voice typing to English.
- **English text, but a different spelling** (e.g. “Hey Sa da”, “Hazada”) → tell me what it shows; we can widen the match safely.

The log contains only what the microphone heard while hands-free was on. It stays on the phone, isn't saved, and disappears when hands-free stops.

**About the sound on every word:** Android Chrome ends its listening session after each thing you say, and Sada must start a new one to keep listening. Android plays its own speech-recognition sound when that happens. A web page can't turn that sound off; a native Android app (see `docs/HANDS_FREE.md`) can avoid it.


## Final review (November 2026): what is and is not verified

| Kind | Result |
|---|---|
| Server tests against a local **fake** of Meta's API (`npm test`) | 98 / 98 pass |
| Android logic tests (Java, simulated audio) | 102 / 102 pass |
| Android Kotlin static checks | clean (compiled for real only by GitHub Actions) |
| Production web build (`vite build`) | **not run here** (no network for dependencies); Vercel builds on deploy |
| **Real Meta API** | **not verified here** (no key, no network). Verify after deploying (below). |
| Physical device | not verified by these tests |

**Live check after deploying** (proves the real Meta API works with your key):
1. In the Android app: enter the address, tap **Save address and test the connection**. "Connected: Muse Spark answered (model …)" means a real Muse Spark call succeeded.
2. Ask one question by voice. In **Share report**, the lines `transcript` and `answer` show a real Muse Voice Transcribe and Muse Spark result.
3. In Vercel → your project → Logs: lines `[ai] op=ask model=… schema=ok` confirm the answer matched the required shape. Logs never contain prompts, answers or keys.

**Changes in this review:**
- `api/transcribe.js`:
  - the size limit now fits the longest allowed recording (30 s at 24 kHz, base64);
  - WAV files are read chunk by chunk (extra chunks such as LIST, odd sizes, streaming data sizes);
  - Meta receives a canonical WAV.
- `api/_meta.js` + `api/ai.js`: every AI answer is checked against the exact schema requested. One retry, then an honest error. The model fallback and the no-`response_format` fallback can no longer pass a different shape to the apps.
- `api/ai.js`: whitespace-only text counts as missing.
- Android:
  - the server address is normalised (`https://` added; plain `http://` refused with a clear message);
  - every report shows the app version and source revision.
