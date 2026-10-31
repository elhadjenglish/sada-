# Hands-free listening: what works, what doesn't, and the native plan

## How it works (web version)

1. Tap the ear button in the player once. Sada starts Chrome's speech recognition and keeps the screen awake.
2. Sada listens for your **wake phrase** (default “Hey Sada”, change it in Settings). Podcast speech without the wake phrase is ignored; nothing is sent to Meta.
3. The moment the wake phrase is heard (even before you finish the sentence), the episode pauses and a short rising tone plays.
4. Sada takes your question: in the same breath (“Hey Sada, what does reliable mean?”) or after the tone. It ends after ~1.8 s of quiet; if you say nothing for 8 s, the episode resumes.
5. **Only the question text** goes to Muse Spark, with the paused sentence as context. One request per question.
6. Sada speaks the answer (English with an English voice, Arabic with an Arabic voice if your phone has one) and shows it on screen. The microphone is **off while Sada speaks**, and anything that repeats the answer is ignored, so Sada can't obey its own voice.
7. Say your **resume phrase** (default “Continue”). Episodes with real audio resume at the exact position; narrated episodes restart the interrupted sentence (the phone voice can't resume mid-sentence). You can also ask another question with the wake phrase first.

Errors are spoken or shown: no answer from Meta (“Sorry, I couldn't get an answer…”), microphone blocked, no microphone, speech service unreachable (keeps retrying with back-off), and the browser ending recognition sessions (restarted automatically).

**Who hears your voice:** wake-phrase listening uses Chrome's speech recognition. If Chrome offers on-device recognition for English, Sada uses it and the status line says “on-device recognition”. Otherwise Chrome sends the audio to Google's speech service while hands-free is on. Meta only receives the text of your question. The mic button (push-to-talk) still uses Muse Voice Transcribe.

## Where it can work

| Setting | Expected | Verified? |
|---|---|---|
| Android Chrome, Sada open, screen on | Should work: continuous recognition, auto-restart, spoken answers | **Not on a real phone.** Verified only with a simulated recognizer in Chromium |
| Android Chrome, screen locked or Chrome in background | **Not expected to work.** Browsers don't keep a page's speech recognition running in the background, and a page can't start a background service | Not tested. The app reports “screen off… the browser may stop listening” and restarts listening when you return |
| Phone in pocket with screen **on** (Sada keeps it awake) | The realistic web option | Not tested. Pocket touches may hit the screen; some Android phones block touches in a pocket |
| Installed PWA | Same Chrome engine and same limits as a tab: **no background microphone** | Not tested; no advantage expected for this feature |
| Native Android app | Can listen with the screen locked using a foreground service | Not built yet (plan below) |

### Bluetooth earphones (applies to web and native)

When an app records from a Bluetooth headset microphone, Android usually switches the earphones from the music profile (A2DP) to the call profile (HFP). That typically means mono, call-quality sound for as long as the mic is open, and some earphones play a connection tone. With continuous wake listening the mic is always open, so the podcast may sound like a phone call. Whether Chrome uses the earphone mic or the phone mic depends on the phone. **This must be checked on your phone** (test step 5).

## Is a native Android app necessary?

**For the exact experience you described (screen locked, phone in pocket): yes.** A web page cannot keep the microphone and speech recognition running once the screen locks, and there is no permission or setting a website can request to change that. The web version works with the screen on (Sada keeps it awake).

### Smallest native version that keeps the React app

Wrap the existing Vite build with **Capacitor** (the React code stays as is) and add one native plugin:

1. **Foreground service, type `microphone`.** Android 11+ requires the type for background mic access. Android 14+ also requires the `FOREGROUND_SERVICE_MICROPHONE` permission, and the service must be **started while Sada is visible**, not from the background. Manifest: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`.
2. **Ongoing notification** while listening (“Sada is listening for Hey Sada”), with a Stop button. Required for foreground services.
3. **Wake-phrase detection inside the service, on the device.** Options to evaluate: Vosk (offline, open-source, can be limited to your phrases), Picovoice Porcupine (custom wake words; check its licence terms), or Android's `SpeechRecognizer` with offline preference (restarts frequently and may beep).
4. **Playback in a native media service** (Media3/ExoPlayer with a `MediaSessionService`), so the episode keeps playing and can be paused and resumed with the screen locked. Earphone buttons and the lock-screen controls come with it.
5. **Audio focus:** request transient focus while Sada speaks the answer, then return it.
6. The service passes the captured question text to the existing `/api/ai` route and speaks the answer with Android `TextToSpeech`. The server, Meta integration, security and rate limits stay unchanged.

Because you build from a phone, the Android build would run in **GitHub Actions** (it produces an APK you download and install). Nothing here has been built or tested yet.
