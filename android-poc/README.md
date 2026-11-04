# Sada Stage 0: native wake-word test (Android)

A small standalone test app. It does **not** contain the Sada React app and does **not** change the web version.

**What it does:** plays a podcast through your earbuds or the phone speaker and listens with the **phone's own microphone** (with echo cancellation when the podcast is on the speaker). On "Hey Sada" it pauses, records your question (with the earbud microphone when earbuds are connected), plays your question back so you can hear how well it was captured, and resumes on "Continue". It keeps running with the screen locked (foreground service with a permanent notification).

**What it doesn't do:** no Meta calls, no internet permission at all (nothing can leave the phone), no keys inside. It's a debug build, for testing only. Wake words are detected offline by sherpa-onnx (Apache-2.0) with its English GigaSpeech keyword model.

Built for: Infinix Hot 40 Pro (64-bit ARM), Android 13, HOCO Bluetooth earbuds.

---

## A. Put Stage 0 in your GitHub repository (from your phone)

You need two things in the repo: the workflow file and the app. On a phone, GitHub's upload page loses folder structure, so the app goes in as one zip.

1. **Workflow file.** In your repo on GitHub: **Add file → Create new file**.
   - Name: `.github/workflows/android-stage0.yml` (typing the `/` creates the folders).
   - Paste the contents of `android-stage0.yml` → **Commit changes**.
2. **The app.** **Add file → Upload files** → choose `android-poc.zip` → **Commit changes**. Upload the zip itself; don't unzip it. It must sit in the repository root, next to `package.json`.

The build starts by itself after the upload. Your web app files are not touched, and Vercel ignores both new files.

## B. Download the debug APK

1. Open the **Actions** tab → **"Android Stage 0: build debug APK"** → the newest run.
2. Wait for the green tick (about 5–10 minutes).
3. Scroll to **Artifacts** at the bottom → tap **sada-stage0-debug-apk-N**. You must be signed in to GitHub. It downloads as a `.zip`.

If the run fails, open the red step, copy the error lines and send them to Claude. To rebuild without changes: **Run workflow** on the same page.

## C. Install on the phone

1. Open the downloaded zip in the Files app and extract it. Inside is `sada-stage0-debug-N.apk`.
2. Tap the APK. Allow **Install unknown apps** for the Files app when asked.
3. If Play Protect warns about an unknown app, choose **Install anyway**.

This is a debug build for testing only. Builds normally install over the previous one. If Android ever says the app "conflicts with an existing package", uninstall Sada Stage 0 first. That happens if GitHub has discarded the saved debug key (after about a week without builds), and it also deletes the test results stored in the app, so copy the report first.

## D. Set up inside the app

1. **Allow microphone and notifications.**
2. **Let Sada run in the background (battery)** → Allow.
3. **Open Sada's app settings** → **Battery** → choose the option that doesn't restrict background use (often **Unrestricted** or **No restrictions**). Infinix XOS names and places these differently between versions. Also look for:
   - **Phone Master** app → auto-start / background management → allow Sada Stage 0;
   - in **Recent apps**, lock the Sada Stage 0 card (pull down or long-press it), if your phone offers it.

   These menu names are **not verified** for your XOS version. The app measures what actually happens instead: the status panel shows the standby bucket, "background restricted", battery optimization, and if Android stopped a test the report says when.
4. **Make the test episode** (Sada's six original episodes, read by the phone's voice, about 15 minutes, loops). Or choose your own podcast file.
5. Connect the HOCO earbuds.

## E. How this build works

**Podcast through the phone speaker.** The podcast is now played by Sada's own decoder instead of Android's media player, so Sada knows exactly what the speaker is playing. An echo canceller subtracts that sound from the phone microphone before wake-word detection, keeping your voice. Without this, the speaker drowned out "Hey Sada", which is why detection failed without earbuds. It is switched on only when the podcast plays through the speaker; with earbuds there is no echo to remove.

**Asking a question.** After "Hey Sada" the podcast pauses and Sada switches to the best microphone:
- **Earbuds connected:** the earbud microphone, close to your mouth. This needs Bluetooth "call mode", which only happens while the podcast is paused. Wait for the beep (about 1–2 s), then ask.
- **No earbuds:** the phone microphone. Ask right after the beep.

Sada records until you stop talking. Stage 0 has no AI answer yet, so it says **"Here is what I heard"** and **plays your question back**, so you can judge with your own ears whether it was captured clearly. Then say **"Continue"**. The recording is kept in memory only and discarded after the playback.

**Detection uses two versions of the microphone signal** and accepts "Hey Sada" from either; the report shows which one caught it.

## F. Tests: in this order, copy the report after each

1. **Self-test** (no microphone): tap **Run detector self-test**.
2. **Earbuds, phone in hand, screen on (2 min).** Play the podcast. Say "Hey Sada", wait for the beep, ask "What does reliable mean?", listen to the playback, then say "Continue". Repeat 5 times.
3. **Earbuds, phone in pocket, screen locked, walking (10 min).** The same cycle 5–10 times. Listen to whether the playback of your question is clear.
4. **No earbuds, podcast on the speaker, phone in hand (2 min).** Say "Hey Sada" 5 times over the podcast. Check the status line **Echo cancellation: ON, echo reduced by … dB**.
5. **Same as 4 with echo cancellation switched off** (untick it). This shows how much it helps on your phone.
6. **No earbuds, phone in pocket, screen locked (10 min).** The full cycle.
7. **30-minute silence test and 30-minute walking test** (as before), once 2–6 work.

**What to listen and look for:**
- **The playback of your question.** Can you understand every word? This is the real test of question capture.
- **Echo cancellation line:** "echo reduced by N dB". On the speaker, more than 15 dB is good; under 6 dB means it isn't working on this phone.
- **Last question line:** which microphone was used, how long it took to get ready, speech level and SNR (speech above background; 15 dB or more is comfortable), and why recording ended.
- **Detections by stream:** which processed signal caught "Hey Sada".

## G. What the report measures

- **Detections and misses:** detected vs your attempts, misses marked by tap, false activations per hour, and "Continue" heard while playing (always false).
- **Timing:** wake → paused, wake → response starts, Continue → playing, and the resume position error.
- **Routing:** microphone device, playback device, audio mode, Bluetooth call audio (SCO) on/off, and route changes during the test.
- **Microphone:** level (how well the phone mic hears you in a pocket), and whether Android silenced it.
- **Service:** uptime, screen on/off, heartbeat, battery use, and any stop by Android (detected from a heartbeat file).
- **Processing:** keyword engine cost per 100 ms of audio.

## H. Known limits (honest list)

- **First compile:** this build has not been compiled yet. The Java signal-processing core and the flow logic were compiled and tested on the build machine (31 tests, simulated signals); the Android Kotlin code compiles for the first time in GitHub Actions.
- **Echo cancellation on real hardware:** it was tested only on synthetic echo. The Infinix speaker, its distortion at high volume, and how accurately Android reports playback and recording timestamps decide the real result. The status line measures it.
- **Pocket plus speaker** is the hardest case: the speaker and microphone are centimetres apart inside fabric. Even with good cancellation, very loud playback may still hide your voice. Moderate volume will work better.
- **Earbud microphone:** switching takes 1–2 s and some earbuds play a tone. Whether the HOCO EW51 microphone is clear in call mode is untested.
- **"Sada" is an unusual word** for the keyword model; detection still depends on how it hears your pronunciation.
- **sherpa-onnx bug:** a keyword starting just after a 1.5 s silence may be missed.

## I. Investigation: intermittent "Hey Sada" detection (November 2026)

### Probable causes (from code, Android documentation and the device report; to be confirmed on the phone)

1. **Input level far below what the model handles well.** Android requires the `VOICE_RECOGNITION` source to have no automatic gain and a fixed calibration: 90 dB SPL ≈ -22 dBFS (CDD 5.4.2). Speech reaching a phone at chest height or in a pocket is about 20–30 dB quieter, so it arrives around -45 to -55 dBFS. That is consistent with the device report (average -70 dBFS, peak 0.08). The self-tests feed the phone's voice at normal levels (about -20 dBFS), which is why they passed while live detection failed. The previous gain control needed about 2.4 s of speech to add 20 dB (too slow for "Hey Sada"), and the second detector stream was the raw signal.
2. **Leaked call-mode state.** Choosing the earbud microphone selects a Bluetooth communication device. In Android's audio policy that forces other recordings to the headset input. If the test was stopped while the switch was in progress, the request was never withdrawn, so the "phone microphone" recorder could capture an idle Bluetooth input (near-silence) in later tests. Android clears such a request when the requesting process dies: that matches "works again after reinstalling".
3. **Unverified recovery.** Nothing checked that the recorder was really back on the phone microphone after a question (Android applies routing changes asynchronously), and a stalled question step could leave the detector muted for the rest of a session.

### Changes

- **Calibration:** a fixed +20 dB gain (+10 dB for the plain-microphone option) with a soft limiter, before everything else. The detector gets a calibrated stream (deterministic) and an adaptive-gain stream; gain control now reacts within about 0.5 s.
- **Call-mode guard:** every earbud-mic path withdraws its request, including stopping mid-switch. Stopping a test always withdraws it. Sada waits until Android reports a clean state before reopening the phone mic, and repairs leftover call mode while listening (logged as "call-mode repair").
- **Recorder self-checks:** about once a second Sada confirms the recorder is on the phone microphone, and reopens it if it was routed elsewhere or delivers 3 s of digital silence (logged as "mic recovery").
- **Watchdog:** any question step stuck for 30 s returns to waiting for "Continue" with the detector listening.
- **Evidence:**
  - every second of audio is classified (silence / noise only / quiet speech / clipped / usable speech);
  - levels are reported at each stage (raw → calibrated → detector);
  - speech seconds with no detector output at all are counted;
  - the self-test runs a level sweep on the phone, and the build machine runs one on real recorded speech;
  - an optional, consented 10-second microphone check checks whether a fresh detector finds "Hey Sada" in what was recorded, as received and at 0/10/20/30 dB of gain.

### Wake-word engines considered

| Engine | Licence / cost | Offline | Custom "Hey Sada" | Decision |
|---|---|---|---|---|
| sherpa-onnx keyword spotting | Apache-2.0, free | Yes | Any phrase, no training | **Kept.** The failures trace to the audio pipeline, not to the engine. |
| Picovoice Porcupine | AccessKey validated online at start-up (needs internet permission). Free-tier keys were reported discontinued after 30 June 2026, with no non-commercial tier. Custom models carry licence limits. | Detection yes; key check no | Trained in Picovoice Console | **Not usable here** without an enterprise contract, so no fair device comparison was possible. Design lessons taken: plain `MIC` source, 512-sample frames, one recorder feeding listeners. |
| openWakeWord / microWakeWord | Open code; pre-trained openWakeWord models are non-commercial; custom models need training | Yes | Requires training a model | Possible later if sherpa-onnx accuracy is the limit once levels are right. |

A real A/B comparison between engines on this phone is only meaningful once the pipeline delivers correct levels. Steps 2–4 of the tests show whether it now does.

## J. Test plan for this build (one variable at a time; copy the report after each)

**Before anything:** Run detector self-test. Step 4 (level sweep) and step 5 (build machine, real speech) show how much detection depends on input level.

Use the same quiet room, normal speaking voice, earbuds connected, podcast playing on the earbuds, and 10 attempts of "Hey Sada" per test, a few seconds apart. Write down how many times you said it.

| Test | Condition | What changes |
|---|---|---|
| A | Phone 5–10 cm from the mouth | distance only |
| B | Phone at chest, 20–30 cm | distance only |
| C | Phone in a trouser or jacket pocket | distance + fabric |
| D | As C, screen locked | screen only |
| E | As D, walking | movement and noise |
| F | As D, 30 minutes, say "Hey Sada" every 2–3 min | duration |
| G | Stop and start the test 5 times (no reinstall), 3 attempts each | restarts |
| H | Silence test for 30 minutes, podcast on the earbuds, then on the speaker | false activations |

**During B, run one microphone check** (with your consent) and say "Hey Sada" 2–3 times while it records. Its result line answers directly whether the phrase was in the audio, and at what gain a fresh detector finds it.

**In each report, the most useful lines are:**
- **Levels:** raw → detector, in dBFS.
- **Seconds:** how much was silence, noise, quiet speech or usable speech.
- **Speech seconds with no detector output.**
- **Mic recoveries and call-mode repairs:** these should stay at 0; if not, the log says why.
- **Microphone check.**

## K. Reliability over time, question capture, activation (this build)

### Findings
- **Questions ran to the 12-second limit.** The end-of-speech detector listened to the audio after gain control. After a quiet question, gain control had raised the background, so silence still looked like speech. Reproduced in a simulation test; the detector now runs on the signal before gain control (the playback still uses the gain-controlled audio). Silence before you start speaking still works, and the recording is still trimmed to the spoken part.
- **Detection got weaker with use (likely cause).**
  - Every test start, self-test and microphone check created new keyword detectors (each an ONNX Runtime session with native memory and threads), and none were ever released. Android keeps the app's process alive between tests, so these piled up until the process died, for example on reinstall. That matches "fresh install good, gradually worse".
  - Smaller items: experimental settings survived updates, and the event log file grew without limit.
  - This build fixes all three and measures the result on the phone (Health line).
- **"Speech with no detector output"** is not a failure count. It included ordinary talking and question speech. It now counts only while listening, and the report says to compare it with your attempts.
- **"Inconsistent gain tests" in the microphone check:** different gains give different results when a phrase sits near the detector's threshold. It is evidence that detection is borderline for that recording, not that gain is broken.

### Changes
- **One live detector per app process,** reused across tests. The self-test and microphone check reuse their own cached detectors. Every stream is released after use.
- **Stream refresh:** live streams are refreshed every ~2 minutes during quiet moments, never mid-phrase, so nothing accumulates over a long session.
- **Health check:** every 5 minutes, while just listening (never during a question), a "Hey Sada" recorded in the phone's voice at session start is run through the live detector. Two failures in a row: the detector is rebuilt and checked again. All logged.
- **Health measurements:** processing speed vs real time, microphone capture lag and dropped audio (overruns), native memory, threads, and open detectors/streams.
- **Settings:** experimental options are reset once for this version; "Restore default settings" button.
- **Earbuds disconnect:** the podcast pauses instead of moving to the phone speaker.

### Activation
- **Voice:** "Hey Sada".
- **Earbuds:** one tap = pause / continue; two quick taps or the earbuds' next-track gesture = Ask Sada; while asking, a tap ends the question.
- **Notification:** lock-screen buttons "Ask Sada" and "Pause / Continue". Plus an "Ask Sada now" button in the app.
- **All lead into the same flow:** pause → question → playback → "Continue".
- **Volume-button gesture: postponed.**
  - Android gives background apps no general access to the volume keys. The only legitimate route is for Sada's media session to take over volume control entirely ("remote volume"), so system volume and Bluetooth volume would no longer behave normally.
  - Every press must still change the volume, so a gesture also changes the volume.
  - Buttons get pressed by accident in pockets, which would mean accidental activations.
  - Earbud controls give the same "without taking the phone out" result through a mechanism Android supports. Revisit if earbud controls prove insufficient.

### Device test plan for this build
1. **Earbud controls.** Play the podcast with the earbuds connected and the phone locked in your pocket. Single-tap: it pauses; single-tap again: it continues. Double-tap: Sada asks for your question. Check the log for `button` lines: they show which key each earbud gesture sends.
2. **Question ending.** "Hey Sada" → wait 3 s → ask → stop talking. The question should end about 1 s after you stop (the "Last question" line says "you stopped talking", not "maximum length"). Repeat 5 times.
3. **Long-term reliability:**
   - On one day without reinstalling, run several test sessions (start/stop at least 5 times), the self-test 3 times, and one microphone check.
   - Then run 10 "Hey Sada" attempts at chest level.
   - Compare with 10 attempts right after installing this build.
   - Send the Health line from both.
   - Native memory and threads should stay roughly flat; the detector check should keep passing.
4. **Two hours of listening.** Play for 2 hours locked in the pocket, with occasional questions. Send the report: health checks passed/failed, rebuilds, overruns, processing speed.
5. **Earbuds disconnect.** Switch the earbuds off while the podcast plays: it should pause, not continue on the speaker.

## L. Experiment: earbud microphone for the whole session (call mode)

**Setting:** "Listening microphone (experiment)". **Phone** = Build #8 behaviour (default). **Earbuds** = the whole session runs in Bluetooth call mode:
- the earbud microphone listens for "Hey Sada" and "Continue" and records the question, with no switching;
- the podcast, answers and tones play as call audio, which is mono and phone-call quality.

Android suspends music-quality Bluetooth (A2DP) while the earbud microphone is in use, so this is the only way to use the earbud mic continuously on classic Bluetooth.

**What the app reports (Live status and report, section "Listening microphone"):**
- Microphone requested vs actually used, and podcast output requested vs actually used.
- Call route status, how often the link was lost, and how often it was restored.
- **Microphone link quality:** wideband (16 kHz) or NARROWBAND (8 kHz). Narrowband removes everything above 4 kHz and may weaken "Hey Sada".
- **Activations:** by voice vs by earbud tap. Double-tap an earbud when "Hey Sada" got no response; each tap counts as a missed voice attempt.
- **Safety:** if the podcast ever lands on the phone speaker, it is paused. If the call route can't be established, the test stops and says why.

**Device test (phone in pocket, screen locked, walking, normal voice):**
1. Select **Earbuds**, choose the test episode, start, lock the phone, put it in your pocket.
2. Listen for 1 minute. Note the podcast quality (1–5).
3. Ten times:
   - say "Hey Sada";
   - if nothing happens within 3 s, double-tap an earbud (that counts as a miss);
   - ask a short question, listen to the playback;
   - say "Continue";
   - if it doesn't resume within 3 s, single-tap.
4. Keep listening for 15 minutes without speaking (false activations).
5. Share the report (**Share report** button). Note battery % at the start and end.

**Pass:**
- at least 8/10 voice activations;
- at least 8/10 "Continue";
- at most 1 false activation in 15 minutes;
- no unrecovered link loss;
- podcast quality acceptable to you.

**Going back to Build #8:** select **Phone** (or "Restore default settings"). To revert the source entirely, upload `android-poc-build8-baseline.zip` renamed to `android-poc.zip`.

## M. Earbud experiment v2: level fix and phrase test

**Changes from v1:**
- **+20 dB calibration on the earbud microphone.** v1 had 0 dB. Two device sessions measured speech at -43 and -51 dBFS, while the level sweep shows "Hey Sada" is only detected at -30 dBFS or louder.
- **The report lists which call-audio effects** (echo canceller, noise suppressor, gain control) Android offers on this phone. Availability only; nothing is enabled.
- **The microphone check is now a 20-second phrase test:**
  - stay silent for 5 s;
  - then say "Hey Sada", "Continue" and "Kada", twice each.
- **The phrase test reports:**
  - background level while you were silent (podcast leakage);
  - microphone link bandwidth;
  - what the live detector found;
  - a fair comparison of all three phrases with equal settings at 0/+10/+20/+30 dB.
- **"Kada" exists only in this offline comparison.** The live detector and production keywords are unchanged.

**Test (earbuds in, podcast playing, Earbuds mode):**
1. Start the test. Tap **Record a 20-second phrase test** and follow the instructions. Repeat once with the podcast paused (notification → Pause / Continue).
2. Do 10 natural "Hey Sada" attempts with the podcast playing. If one gets no response within 3 s, double-tap an earbud.
3. Tap **Share report** and send it.

The phone's position doesn't affect what the earbud microphone hears, so the phrase test can be done with the phone in your hand. Do the 10 natural attempts with the phone locked in your pocket.

**Versions kept:**
- `android-poc-build8-baseline.zip`: Build #8.
- `android-poc-earbud-experiment-v1.zip`: the previous experiment.
- This package: v2.

To use one of them, upload it renamed to `android-poc.zip`.

## N. Phone Speaker Mode (primary hackathon demo)

**Usage mode: Phone speaker (default).**
- The phone lies on the desk 30–80 cm from you.
- The podcast plays on the phone's own speaker (forced, even if earbuds are connected).
- The phone microphone listens for "Hey Sada", and Sada removes the podcast from what it hears using the podcast's own audio as a reference.
- Questions and "Continue" also use the phone microphone.

**Fixes made for this mode (simulation, not yet device-tested):**
- **Echo cancellation now runs on the raw microphone signal, before the +20 dB calibration gain.** Before, the gain's limiter distorted the loud speaker echo so the canceller could not remove it. In simulation: 14 → 39 dB echo reduction; your voice 11 → 26 dB above the podcast going into the detector.
- **No-harm rule:** if cancellation would ever make the signal louder than the microphone (for example just after the podcast resumes), the detector gets the plain microphone for that moment.
- **Known limit:** after each "Continue" the canceller re-learns the room for about 3 seconds. In simulation it's back to 18 dB within 3–5 s, and never worse than no cancellation.

**Echo cancellation choice (for comparison):** Sada's own (default) / Android built-in (experimental; the report says whether Android actually enabled it) / Off.

**Device test (phone on the desk, screen locked, podcast on the speaker at your normal listening volume):**
1. **Phrase test.** Start the test, tap **Record a 20-second phrase test**, put the phone down at 50 cm. Stay silent for 5 s, then say "Hey Sada", "Continue", "Kada" twice each.
2. **Natural attempts.** Lock the phone. At **30, 50 and 80 cm**, say "Hey Sada" 5 times each while the podcast plays. After each detection, ask a short question, then say "Continue". If "Hey Sada" gets no response within 3 s, tap **I said “Hey Sada” but nothing happened** in the app; that marks a miss.
3. **False activations.** Let the podcast play for 15 minutes without speaking.
4. **Comparison.** Repeat step 2 at 50 cm with echo cancellation **Off**, then with **Android built-in**.
5. Tap **Share report** after each step and send it.

**Pass:**
- at least 8/10 "Hey Sada" at 50 cm with the podcast playing;
- "Continue" works every time;
- at most 1 false activation in 15 minutes.

**Report lines to read:**
- **Echo cancellation:** echo reduced by N dB.
- **Phrase test:** background reduced by N dB, and which phrases were found.
- **Activations:** how many succeeded by voice.

**Versions kept:**
- **android-poc-build8-baseline.zip:** Build #8.
- **android-poc-earbud-experiment-v1.zip and -v2.zip:** the earbud experiments.
- **This package:** Phone Speaker Mode.

To switch to one of them, upload it renamed to `android-poc.zip`.

## O. Speaker-mode diagnosis build

**Findings from the device reports (A, B, C):**
- **The podcast reaches the microphone about as loud as your voice.** Raw average about -40 dBFS with the podcast playing; your speech -41 dBFS. All 20 phrase-test seconds counted as speech, including the 5 you were silent. Raising the gain raises both equally, which explains zero detections at every gain.
- **No bug was found in the recording or recognition path.** The phrase recorder stores the untouched microphone and the exact detector stream, and both are fed to the detector by the same function as the passing self-test.
- **"Android effects ENABLED" in the OFF report was a stale status** from the earlier Android-AEC session in the same app process. Fixed: each session now reports only its own request.
- **Android's built-in echo canceller produces digital silence on this phone.** It stays experimental, and off by default.

**New in the phrase test** (podcast playing on the speaker; stay silent 5 s, then the phrases):
- **Your voice vs the podcast:** how far your voice was above the podcast at the microphone.
- **Echo delay:** measured from the recording itself, and whether it falls inside the window Sada's canceller can handle (-32 to +224 ms). If it's outside, that explains the 3 dB result.
- **Positive control:** the known-good "Hey Sada" mixed into *your* recorded podcast background at the same level, +10 dB and +20 dB. Shows how much louder than the podcast the phrase must be for this detector.

## P. Primary interaction: double-press Volume Up

**Controls:**
- **Double-press Volume Up (+):** while the podcast plays, Question Mode (the podcast pauses, the microphone opens). While asking, it finishes the question. While paused, it resumes.
- **Saying "Continue"** also resumes. Single presses change the podcast volume.
- **Lock-screen notification:** **Ask Sada** and **Pause / Continue** always work.
- **Earbuds:** double-tap = ask; single tap = pause / continue.

**When the volume keys reach Sada (Android rules):**

| State | How | Status |
|---|---|---|
| Sada open on screen | the app reads the keys directly | reliable (standard Android) |
| Background or locked | Sada's media session takes over volume ("remote volume"), so Android sends volume presses to Sada | **must be verified on the Infinix:** Android/XOS decide whether keys reach a media session with the screen off |
| Accessibility service | not used: invasive permission meant for accessibility | rejected |

**The report shows how each press arrived:** "Volume keys received: app on screen N, media session M".

**What else changed:**
- **Microphone:** it is only on while you ask a question or while Sada waits for "Continue". It closes when the podcast resumes.
- **Voice wake word "Hey Sada":** now an option, off by default.
- **Notification:** shows the current state.

**Device test (podcast on the phone speaker or earbuds, normal volume):**
1. **Screen on, Sada open:** double-press Volume Up, ask a question, double-press again to resume. Do 5 times.
2. **Single presses:** press Volume Up and Down once each; the podcast volume should change and nothing else should happen. Hold Volume Up for 2 s: volume only, no Question Mode.
3. **Screen off, phone in pocket:** lock the phone and repeat step 1 five times. Then **Share report** and check "Volume keys received: media session …".
4. **Another app open** (e.g. the browser) with the podcast playing: repeat step 1 once.
5. **Ask without speaking:** double-press, stay silent. After about 6 s Sada says it heard nothing. Double-press to resume.

## Q. Fix: locked-screen double press while waiting for "Continue"

**Cause:** Android only sends volume keys pressed while the screen is locked to a media session that reports an *active* playback state (`MediaSessionStack.getDefaultVolumeSession()` requires `checkPlaybackActiveState(true)`). While waiting for "Continue", Sada's session reported *paused*, so Android skipped it and the presses never reached Sada. With Sada open on screen the app reads the keys directly, which is why that case worked.

**Fix:** while Sada waits for you (for "Continue", or while you ask a question), its session reports an active "waiting" state (buffering, speed 0). The podcast stays paused. While Sada answers, the volume keys still control the system volume as before.

**Report lines:**
- "Media session reports …": the state Android sees.
- Log lines "volume-key": each press, its route ("app on screen" or "media session"), and the state at that moment.
- Log lines "volume-gesture": the gesture decision, and whether the podcast was playing again 0.5 s after a resume.

## R. Question start: microphone readiness and "Go ahead, I'm listening"

**Cause of lost question beginnings (earbud microphone):**
1. After the double press, the phone microphone was closed while call mode was requested.
2. A fixed 300 ms wait followed Android reporting the route.
3. Then a short beep, and only *after* that was the earbud recorder opened.
4. Its first 300 ms were thrown away.

For about 1.5–2 s nothing recorded at all, and you had no clear cue. Speech in that time was lost, and the end-of-speech detector measured your voice as "background".

**Now:**
1. **Double press:** the podcast pauses at once, and the state shows "getting the microphone ready… wait for Go ahead".
2. **The earbud recorder opens as soon as Android reports the route.** The microphone counts as ready only when it delivers **live audio** (about 100 ms of non-silent blocks). Android reporting the route is not enough.
3. **Then Sada says "Go ahead, I'm listening."** On earbuds the cue plays as call audio, so you hear it in the earbuds.
4. **Recording starts only after the cue has finished.** The recorder's backlog is discarded first, so the cue never becomes part of the question.
5. **The background noise measured while getting ready** seeds the end-of-speech detector. Speech right after the cue is no longer mistaken for background.
6. **If you were already talking** while it got ready, Sada says "Sorry, I missed the start. Go ahead, I'm listening."
7. **If the earbud mic doesn't connect in 4 s, gives no audio in 1.5 s, or Bluetooth drops:** call mode is released, the phone mic is used, and Sada says "The earbud microphone isn't ready. Go ahead, I'm listening on the phone."

**Phone microphone** (speaker mode, or no earbuds): the same cue, given once the phone mic is delivering audio.

**Report:** "mic-ready" log lines show route time, live-audio time, the mic used, and what Sada said.

## S. Earbuds disconnecting during a question

**Cause:** the earbud recording only treated a *read error* as failure. When Bluetooth disconnects, Android usually keeps the recorder open and delivers digital silence (zeros), so Sada kept "recording" nothing until it gave up. The device report showed -102 dBFS, peak 0.00. Fallback to the phone existed only *before* "Go ahead".

**Now (every stage):**
- **Preparing** (before "Go ahead"): no live audio in 1.5 s → phone mic, "The earbud microphone isn't ready. Go ahead, I'm listening on the phone." (unchanged).
- **After "Go ahead" or while asking:** every 16 ms block is checked. The mic counts as lost after 0.4 s of digital silence once Android no longer reports the earbud route, or after 2 s of digital silence regardless. A route change while audio still flows is never a loss.
- **If you had clearly finished your question** (at least 0.7 s of speech, then 0.5 s of silence), that recording is kept and answered.
- **Otherwise** the earbud recording is discarded (never mixed with the phone mic). Call mode is released, the phone mic must deliver live audio, then Sada says "The earbud microphone disconnected. Go ahead, I'm listening on the phone." and records your question again, in the same question session.
- **If the phone mic also gives no audio,** Sada says "Sorry, no microphone is working right now." and nothing is answered.
- **A recording is only answered if it contains real speech** (detected speech, a real level, at least 0.3 s). Silent recordings get "I didn't hear a question".

**Log lines:** "mic-lost" (reason, speech so far, decision) and "mic-ready" (which mic, when live audio arrived).

## T. Real answers: transcription + Muse Spark (final MVP)

**Flow:**
1. Double-press Volume Up (or "Hey Sada" if enabled). The podcast pauses.
2. "Go ahead, I'm listening." Ask your question.
3. **Recording stops automatically 1.4 s after you stop speaking.** Thinking pauses shorter than that don't end it. Up to 8 s to start; up to 20 s of question, counted from your first word.
4. A short tone means "thinking".
5. The question goes to **Muse Voice Transcribe**, then **Muse Spark**. Sada **speaks the real answer**: English, Arabic, or both. Your recording is never played back.
6. If the question was about a word, it is saved with its sentence ("Saved words" in the app).
7. "Say continue, or double-press volume up…". The podcast resumes where it stopped.

**Setup in the app (once):** enter your Sada web address (e.g. `https://your-sada.vercel.app`), tap **Save address and test the connection**, and choose the answer language.

**Context sent to Muse Spark:** podcast title and position. For the built-in test episode, the sentences around the pause point too, marked approximate. Re-create the test episode once with this version so its timing index exists. For your own audio files: title and position only.

**Failures are said, never faked:** no speech, not understood, no internet, timeout, server not set up (no key), too many requests.

**Arabic answers** need an Arabic text-to-speech voice on the phone (Settings → Text-to-speech → install Arabic). Without one, Sada says so, and the Arabic answer is shown in the app.
