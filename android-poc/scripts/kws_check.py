"""Informational CI check: run the same keyword model and keywords on synthetic speech (espeak-ng).
Robotic voices are not real speech: this only proves the keywords file works, not real-world accuracy."""
import os, subprocess, tempfile, wave
import numpy as np
import sherpa_onnx

M = os.environ["MODEL_DIR"]
kws = sherpa_onnx.KeywordSpotter(
    tokens=f"{M}/tokens.txt",
    encoder=f"{M}/encoder-epoch-12-avg-2-chunk-16-left-64.onnx",
    decoder=f"{M}/decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
    joiner=f"{M}/joiner-epoch-12-avg-2-chunk-16-left-64.onnx",
    num_threads=1,
    keywords_file=os.environ["KEYWORDS_FILE"],
    provider="cpu",
)

def speech(text):
    with tempfile.TemporaryDirectory() as d:
        p = os.path.join(d, "a.wav")
        subprocess.run(["espeak-ng", "-v", "en-us", "-s", "150", "-w", p, text], check=True)
        with wave.open(p) as w:
            sr, x = w.getframerate(), np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768
    n = int(len(x) * 16000 / sr)
    return np.interp(np.linspace(0, len(x) - 1, n), np.arange(len(x)), x).astype(np.float32)

def spot(samples):
    s = kws.create_stream()
    s.accept_waveform(16000, np.zeros(8000, dtype=np.float32))
    s.accept_waveform(16000, samples)
    s.accept_waveform(16000, np.zeros(16000, dtype=np.float32))
    s.input_finished()
    hits = []
    while kws.is_ready(s):
        kws.decode_stream(s)
        r = kws.get_result(s)
        if r:
            hits.append(r)
            kws.reset_stream(s)
    return hits

cases = {
    "hey sada": True, "hey sada what does reliable mean": True, "continue": True,
    "good morning": False, "she explained that she was reliable": False, "they said a lot": False,
    "hey siri what time is it": False, "please continue reading the book": None,
}
for text, want in cases.items():
    hits = spot(speech(text))
    tag = "info" if want is None else ("ok  " if bool(hits) == want else "MISS" if want else "FALSE")
    print(f"{tag}  {text!r:45} -> {hits}")
