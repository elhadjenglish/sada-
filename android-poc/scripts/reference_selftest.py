"""Reference run on the build machine: the model's own sample recordings through Python sherpa-onnx,
fed the same way the Android app feeds them (0.5 s silence, audio, 1 s silence, 100 ms pieces).
Writes expected.tsv, which the phone's self-test compares against. Also checks that the
"/"-separated keyword string used by the app's near-miss listener is accepted."""
import glob, os, sys, wave
import numpy as np
import sherpa_onnx

model, out_dir, app_keywords = sys.argv[1], sys.argv[2], sys.argv[3]
def pick(prefix):
    c = sorted(f for f in glob.glob(f"{model}/{prefix}-*.onnx") if ".int8." not in f)
    return c[0]

def spotter(keywords_file):
    return sherpa_onnx.KeywordSpotter(
        tokens=f"{model}/tokens.txt", encoder=pick("encoder"), decoder=pick("decoder"), joiner=pick("joiner"),
        keywords_file=keywords_file, num_threads=1, provider="cpu",
    )

def load(path):
    with wave.open(path) as w:
        assert w.getsampwidth() == 2, "16-bit PCM expected"
        x = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
        if w.getnchannels() > 1:
            x = x.reshape(-1, w.getnchannels()).mean(axis=1)
        sr = w.getframerate()
    if sr != 16000:
        n = int(len(x) * 16000 / sr)
        x = np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)
    return x

def run(kws, stream, audio):
    hits = []
    padded = np.concatenate([np.zeros(8000, np.float32), audio, np.zeros(16000, np.float32)])
    for i in range(0, len(padded), 1600):
        stream.accept_waveform(16000, padded[i:i + 1600])
        while kws.is_ready(stream):
            kws.decode_stream(stream)
        r = kws.get_result(stream)
        if r:
            hits.append(r.strip())
            kws.reset_stream(stream)
    return hits

kws = spotter(f"{out_dir}/keywords.txt")
rows = []
for path in sorted(glob.glob(f"{out_dir}/*.wav")):
    hits = run(kws, kws.create_stream(), load(path))
    rows.append(f"{os.path.basename(path)}\t{'|'.join(hits)}")
    print(f"reference: {os.path.basename(path)} -> {hits or 'nothing'}")
with open(f"{out_dir}/expected.tsv", "w", encoding="utf-8") as f:
    f.write("\n".join(rows) + "\n")
print(f"wrote {out_dir}/expected.tsv ({len(rows)} recordings)")

# Level sensitivity on REAL recorded speech: scale each recording so its speech sits at each level.
def speech_db(x):
    n = len(x) // 256
    if n == 0:
        return -120.0
    b = x[: n * 256].reshape(n, 256)
    db = 10 * np.log10((b ** 2).mean(axis=1) + 1e-14)
    db.sort()
    return float(db[n // 2 :].mean())

rows = []
wavs = sorted(glob.glob(f"{out_dir}/*.wav"))
for target in (-20, -30, -40, -50):
    found = 0
    for path in wavs:
        x = load(path)
        y = np.clip(x * 10 ** ((target - speech_db(x)) / 20), -0.98, 0.98).astype(np.float32)
        if run(kws, kws.create_stream(), y):
            found += 1
    rows.append(f"{target} dBFS\t{found}/{len(wavs)}")
    print(f"level sensitivity: speech at {target} dBFS -> detections in {found}/{len(wavs)} recordings")
with open(f"{out_dir}/level-sensitivity.tsv", "w", encoding="utf-8") as f:
    f.write("\n".join(rows) + "\n")

# The app's near-miss listener and sensitivity options pass keywords as one "/"-separated string.
app_lines = [l.strip() for l in open(app_keywords, encoding="utf-8") if l.strip()]
k2 = spotter(app_keywords)
s = k2.create_stream("/".join(app_lines))
run(k2, s, np.zeros(16000, np.float32))
print('OK: a "/"-separated keyword string is accepted by create_stream()')
