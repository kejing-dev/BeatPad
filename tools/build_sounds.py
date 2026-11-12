"""BeatPad drum samples: freesound.org CC0 drum recordings + Kenney CC0 woodblock / UI ticks (trim / fade / filter, no synthesis). Writes 16-bit mono 48 kHz wav into assets/sounds."""
import subprocess, numpy as np, sys, os, wave
from scipy.signal import butter, sosfilt
SR = 48000
K = os.path.expanduser("~/Downloads/cc0-sounds")
IMP = K + "/kenney_impact-sounds/Audio/"; CAS = K + "/kenney_casino-audio/Audio/"; UI = K + "/kenney_interface-sounds/Audio/"
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
rows = []

def load(p):
    raw = subprocess.run(["ffmpeg", "-v", "quiet", "-i", p, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"], capture_output=True).stdout
    x = np.frombuffer(raw, dtype=np.float32).astype(np.float64)
    return x

def trim(x, maxlen, thr=0.02):
    a = np.abs(x); pk = a.max() or 1
    i0 = max(0, int(np.argmax(a > pk * thr)) - 24)
    x = x[i0:i0 + int(maxlen * SR)]
    n = len(x); f = min(int(0.03 * SR), n // 3)
    x = x.copy(); x[n - f:] *= np.linspace(1, 0, f) ** 2
    x[:24] *= np.linspace(0, 1, 24)
    return x

def filt(x, kind, fc, order=2):
    sos = butter(order, fc, btype=kind, fs=SR, output="sos"); return sosfilt(sos, x)

def norm(x, db=-1.0):
    pk = np.abs(x).max() or 1; return x / pk * 10 ** (db / 20)

def pitch(x, ratio):
    n = int(len(x) / ratio); t = np.arange(n) * ratio
    return np.interp(t, np.arange(len(x)), x)

def mix(*parts):
    n = max(len(p) for p, _, _ in parts); y = np.zeros(n + int(0.05 * SR))
    for p, off, g in parts:
        o = int(off * SR); y[o:o + len(p)] += p * g
    return y

def save(name, x, src):
    x = norm(x)
    y = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(os.path.join(out, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(y.tobytes())
    rows.append((name, src, len(x) / SR))

def rec(name, files, maxlen, proc=lambda x: x, note=""):
    xs = [trim(load(f), maxlen) for f in files]
    save(name, proc(xs[0] if len(xs) == 1 else mix(*[(x, 0, 1) for x in xs])), "CC0 录音 Kenney " + " + ".join(os.path.basename(f) for f in files) + (" · " + note if note else ""))

def two(kit, pad, light, heavy, maxlen, proc=lambda x: x, note=""):
    # light layer = the softer take, darker (a soft hit has less top end); heavy = the full take
    rec(f"{kit}_{pad}_h", heavy, maxlen, proc, note)
    rec(f"{kit}_{pad}_l", light, maxlen, lambda x: filt(proc(x), "low", 5000), note + "（轻层：同一类录音另一条，低通 5 kHz）")

# ---------------- drums: freesound.org CC0 recordings (previews, fetched 2026-11-12 into ~/Downloads/cc0-sounds/freesound-beatpad,
# sources in meta.json there and in build/sound-sources.md). Heavy layer = one take, light layer = another take of the same
# instrument (or the same take) low-passed at 5 kHz: a soft hit has less top end.
FS = K + "/freesound-beatpad/"
import json as _json
META = {g["file"]: g for v in _json.load(open(FS + "meta.json")).values() for g in v}
def fsrc(f): g = META[f]; return f"CC0 录音 freesound #{g['id']} {g['title'].replace('Freesound - ', '')} {g['url']}"
PICK = {
 # pad: (heavy file, light file, max seconds, high-pass Hz or 0, note)
 "acoustic_p1": ("acoustic_p1_634837.mp3", "acoustic_p1_140537.mp3", 0.25, 0, "闭镲"),
 "acoustic_p2": ("acoustic_p2_340471.mp3", "acoustic_p2_673516.mp3", 0.35, 0, "军鼓"),
 "acoustic_p3": ("acoustic_p3_685865.mp3", "acoustic_p3_689557.mp3", 0.45, 0, "底鼓"),
 "acoustic_p4": ("acoustic_p4_221484.mp3", "acoustic_p4_378378.mp3", 0.6, 0, "落地嗵"),
 "acoustic_p5": ("acoustic_p5_509985.mp3", "acoustic_p5_91683.mp3", 0.9, 0, "开镲"),
 "acoustic_p6": ("acoustic_p6_378681.mp3", "acoustic_p6_566896.mp3", 0.45, 0, "高嗵"),
 "acoustic_p7": ("acoustic_p7_493717.mp3", "acoustic_p7_537735.mp3", 0.35, 0, "拍手"),
 "acoustic_p8": ("acoustic_p8_93907.mp3", "acoustic_p8_567213.mp3", 1.4, 0, "叮叮镲"),
 "electronic_p1": ("electronic_p1_519308.mp3", "electronic_p1_334063.mp3", 0.2, 0, "闭镲（TR-808 类）"),
 "electronic_p2": ("electronic_p2_455642.mp3", "electronic_p2_278983.mp3", 0.4, 0, "军鼓（808 类）"),
 "electronic_p3": ("electronic_p3_208447.mp3", "electronic_p3_482067.mp3", 0.7, 0, "底鼓（808 类）"),
 "electronic_p4": ("electronic_p4_520169.mp3", "electronic_p4_455643.mp3", 0.6, 0, "低嗵（808 类）"),
 "electronic_p5": ("electronic_p5_519307.mp3", "electronic_p5_519307.mp3", 0.6, 0, "开镲（TR-808 类）"),
 "electronic_p6": ("electronic_p6_455645.mp3", "electronic_p4_520174.mp3", 0.5, 0, "高嗵（808 类）"),
 "electronic_p7": ("electronic_p7_244366.mp3", "electronic_p7_542837.mp3", 0.35, 0, "拍手（808 类）"),
 "electronic_p8": ("electronic_p8_849607.mp3", "electronic_p8_849607.mp3", 0.4, 0, "牛铃（808 类）"),
 "hand_p1": ("hand_p1_858023.mp3", "hand_p1_701087.mp3", 0.3, 0, "沙锤"),
 "hand_p2": ("hand_p2_517522.mp3", "hand_p2_517522.mp3", 0.3, 0, "卡洪高音"),
 "hand_p3": ("hand_p3_517523.mp3", "hand_p3_642476.mp3", 0.45, 0, "卡洪低音"),
 "hand_p4": ("hand_p4_512080.mp3", "hand_p4_475824.mp3", 0.6, 0, "康加低"),
 "hand_p5": ("hand_p5_219266.mp3", "hand_p5_648740.mp3", 0.4, 0, "铃鼓"),
 "hand_p6": ("hand_p6_412445.mp3", "hand_p6_512073.mp3", 0.45, 0, "康加高"),
 "hand_p7": ("hand_p7_219157.mp3", "hand_p7_219157.mp3", 0.35, 0, "邦戈"),
 "hand_p8": ("hand_p8_75339.mp3", "hand_p8_148931.mp3", 0.4, 0, "牛铃"),
}
for key, (hf, lf, mx, hp, note) in PICK.items():
    for lay, f in (("h", hf), ("l", lf)):
        x = trim(load(FS + f), mx)
        if hp: x = filt(x, "high", hp)
        if lay == "l": x = filt(x, "low", 5000)
        save(f"{key}_{lay}", x, fsrc(f) + f" · {note}" + ("（轻层，低通 5 kHz）" if lay == "l" else ""))

# ---------------- metronome + UI
rec("metro_hi", [IMP + "impactWood_light_000.ogg"], 0.08, lambda x: filt(x, "high", 300), "节拍器重拍：木头轻击（木鱼感）")
rec("metro_lo", [IMP + "impactWood_light_001.ogg"], 0.08, lambda x: filt(x, "high", 300), "节拍器弱拍：另一条木头轻击（播放时 −6 dB）")
rec("ui_tick", [UI + "tick_002.ogg"], 0.08, lambda x: x, "UI：删除石子 / 旋钮吸附的轻 tick")
rec("ui_snap", [UI + "click_002.ogg"], 0.08, lambda x: x, "UI：旋钮吸附 80 / 100 / 120")

with open(os.path.join(out, "..", "..", "..", "..", "..", "sound-rows.tsv"), "w") as f:
    for n, s, d in rows: f.write(f"{n}\t{s}\t{d:.2f}\n")
print(len(rows), "files")
