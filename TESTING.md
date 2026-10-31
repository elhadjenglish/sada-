# Testing

There are two kinds of tests. They prove different things.

## 1. Mocked tests (done, in this repo)

`npm test` runs `tests/server.test.mjs` (17), `tests/exercises.test.mjs` (13), `tests/grade.test.mjs` (13) and `tests/handsfree.test.mjs` (19). 62 tests in total. The first three run the real `/api/ai` and `/api/transcribe` code against a **local fake of Meta's API**; the hands-free tests drive the real controller with a **simulated** recognizer, player, voice and clock. They cover:

- Chat Completions request shape from Meta's docs: `developer` role, `response_format: json_schema`, `max_completion_tokens`, `reasoning_effort`, default temperature, hashed `safety_identifier`
- Explanation language: `both` returns Arabic and English; `ar` / `en` return only that language
- Fallback from `muse-spark-1.3` to `META_FALLBACK_MODEL` when Meta says the model is unavailable
- Meta errors (400, 401, 429, 500, 504, empty answer, cut-off answer, non-JSON) become safe messages; the key never appears in a response
- A Meta request that never answers ends with a timeout error before Vercel's 60-second limit
- Hands-free (simulated): wake phrase on interim results pauses immediately; one-breath and two-step questions; question ends on silence; no question → resume; custom phrases and small mishearings; podcast speech ignored; only the question sent; one call per question; recognizer off while speaking; echo ignored; follow-up questions; resume at the saved position; AI failure and voice failure; session restarts and network back-off; permission denied; resume while thinking; visibility
- Open-answer grading: the bug-report example (three valid answers to the “breath” translation all accepted), what the grader is told, minor grammar vs real errors, wrong/partial meaning, missing target word, other translations and explanations, feedback language. These check Sada's prompt and verdict logic; how the real model fills the rubric is only checked live (step 6b below)
- Personal exercises: strict per-type schema; valid, Markdown-wrapped, malformed, cut-off and list-less answers; alternative field names and type spellings; words not in the list; incomplete items retried once then reported as an error; every accepted exercise also passes the app's own validator; logs contain no key or user content
- Input validation, method/origin/content-type/size checks, per-IP and global rate limits
- Transcribe multipart matches Meta's reference (JSON `request` part without filename, WAV `audio` part, `Accept: application/json`); bad WAV files are refused before reaching Meta

### Hands-free in the real UI (`tests/browser/handsfree.browser.mjs`, 27 checks, all pass)

Real Sada screens, real `<audio>` playback and the real `/api` request path, with the **speech recognizer and speech synthesis simulated**. Checks the whole flow: wake → pause → only the question sent → answer spoken (Arabic with an Arabic voice) → recognizer off while speaking → echo ignored → “Continue” → playback restarted at the paused position with no seek (`pause()` and `play()` both at the same time stamp) → narrated episode back on the same sentence; plus session restarts, API failure and microphone denial.

**Not verified anywhere:** a real microphone, real speech recognition accuracy, Bluetooth earphones, the locked screen, Android's start beeps, and real Meta answers in this flow.

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

1. Connect the earphones. Open an episode, tap play, then tap the ear button. Allow the microphone. **Expect:** “Hands-free on. Say “Hey Sada”…”.
2. While it plays, say “Hey Sada, what does reliable mean?”. **Expect:** pause + rising tone straight away, then the spoken answer in the earphones.
3. Say “Continue”. **Expect:** the episode carries on where it stopped.
4. Say only “Hey Sada”, wait for the tone, then ask a question. Then try saying nothing after the tone: it should resume after about 8 seconds.
5. **Listen to the podcast sound quality** before and after turning hands-free on. If it becomes muffled or phone-call-like, the earphones switched to the call profile (see `docs/HANDS_FREE.md`). Note whether Chrome uses the earphone mic or the phone mic (try asking with the phone in your pocket).
6. Change both phrases in Settings and repeat step 2 with the new ones.
7. Turn off Wi-Fi and mobile data and ask a question. **Expect:** a spoken apology or a “can't reach the speech service” status, and “Continue” still works once you're back online.
8. **Screen locked:** start hands-free, lock the phone, ask a question. **Expected to fail** in the browser. Unlock and check that the status recovers and listening resumes.
9. Note anything odd: system beeps when listening restarts, missed wake phrases, answers triggered by the podcast itself.

Tell me the results of steps 2, 5 and 8 in particular: they decide whether we build the native app next.
