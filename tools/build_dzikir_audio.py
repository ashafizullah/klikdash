#!/usr/bin/env python3
"""
Siapkan rekaman dzikir untuk diunduh aplikasi: potong hening di awal & akhir, samakan kekerasan suara dengan murattal
Alafasy (-17 LUFS, puncak -2 dBFS), lalu simpan sebagai MP3 mono 64 kbps di audio/dzikir/<nama>.mp3 beserta
audio/dzikir/index.json (ukuran & SHA-1 tiap file; aplikasi mengunduh ulang file yang SHA-1-nya berubah).

    python3 tools/build_dzikir_audio.py ~/Downloads/dzikir            # semua rekaman
    python3 tools/build_dzikir_audio.py ~/Downloads/dzikir amsaina    # hanya rekaman yang diperbaiki

Rekaman di folder sumber bernama <nama>.<ext> (m4a, wav, mp3, ...) atau nomor urut NN.<ext> sesuai NAMES di bawah
(urutan lembar baca rekam-dzikir). Nama yang dipakai tiap bacaan ada di field "audio" assets/dzikir.json.
Butuh ffmpeg dan numpy.
"""
import hashlib
import json
import os
import re
import subprocess
import sys

import numpy as np

NAMES = ['ashbahna', 'amsaina', 'bika_ashbahna', 'bika_amsaina', 'fitrah_pagi', 'fitrah_petang', 'sayyidul_istighfar',
         'aafini', 'afwa', 'alimal_ghaib', 'bismillah', 'radhitu', 'ya_hayyu', 'tahlil', 'tasbih', 'istighfar',
         'tasbih_adada', 'ilman_nafia', 'audzu_bikalimat']
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'audio', 'dzikir')

# Bicara dianggap mulai pada frame 20 ms pertama di atas ON dB dan selesai pada frame terakhir di atas OFF dB; sisakan
# PRE detik sebelum dan POST detik sesudahnya supaya konsonan lembut di awal dan dengung di akhir tidak terpotong.
ON, OFF, PRE, POST = -40, -45, 0.30, 0.40
MONO = 'pan=mono|c0=0.5*c0+0.5*c1'


def source(src, i, name):
    for f in sorted(os.listdir(src)):
        stem = os.path.splitext(f)[0]
        if stem == name or stem == '%02d' % (i + 1):
            return os.path.join(src, f)
    return None


def speech(f):
    """(awal, akhir, durasi) dalam detik."""
    raw = subprocess.run(['ffmpeg', '-v', 'error', '-i', f, '-ac', '1', '-ar', '16000', '-f', 's16le', '-'],
                         capture_output=True, check=True).stdout
    x = np.frombuffer(raw, np.int16).astype(np.float32) / 32768
    w = 320
    n = len(x) // w
    db = 20 * np.log10(np.sqrt(np.mean(x[:n * w].reshape(n, w) ** 2, axis=1)) + 1e-9)
    if not (db > ON).any():
        raise SystemExit(f'{f}: tidak ada suara di atas {ON} dB')
    start = np.argmax(db > ON) * 0.02
    end = (n - np.argmax(db[::-1] > OFF)) * 0.02
    return start, end, len(x) / 16000


def encode(f, dst):
    s, e, dur = speech(f)
    a, b = max(0, s - PRE), min(dur, e + POST)
    chans = int(subprocess.run(['ffprobe', '-v', 'error', '-select_streams', 'a:0', '-show_entries', 'stream=channels',
                                '-of', 'csv=p=0', f], capture_output=True, text=True, check=True).stdout.strip())
    pre = (MONO + ',' if chans == 2 else '') + (f'atrim={a:.2f}:{b:.2f},asetpts=PTS-STARTPTS,highpass=f=70,'
                                                f'afade=t=in:d=0.03,afade=t=out:st={b - a - 0.25:.2f}:d=0.25')
    target = 'loudnorm=I=-17:TP=-2:LRA=11'
    m = subprocess.run(['ffmpeg', '-hide_banner', '-i', f, '-af', pre + ',' + target + ':print_format=json', '-f', 'null', '-'],
                       capture_output=True, text=True, check=True).stderr
    j = json.loads(re.search(r'\{[^{}]*"input_i"[^{}]*\}', m).group(0))
    ln = (f"{target}:measured_I={j['input_i']}:measured_TP={j['input_tp']}:measured_LRA={j['input_lra']}"
          f":measured_thresh={j['input_thresh']}:offset={j['target_offset']}:linear=true")
    subprocess.run(['ffmpeg', '-hide_banner', '-v', 'error', '-y', '-i', f,
                    '-af', pre + ',' + ln + ',alimiter=limit=0.79:level=false:attack=2:release=50,aresample=44100',
                    '-ac', '1', '-c:a', 'libmp3lame', '-b:a', '64k', '-map_metadata', '-1', '-id3v2_version', '0', dst],
                   check=True)
    return a, dur - b, b - a


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    src, only = sys.argv[1], set(sys.argv[2:])
    unknown = only - set(NAMES)
    if unknown:
        raise SystemExit('nama tidak dikenal: ' + ', '.join(sorted(unknown)))
    os.makedirs(OUT, exist_ok=True)
    for i, name in enumerate(NAMES):
        if only and name not in only:
            continue
        f = source(src, i, name)
        if f is None:
            print(f'{name:20s} rekaman tidak ada, dilewati')
            continue
        head, tail, d = encode(f, os.path.join(OUT, name + '.mp3'))
        print(f'{name:20s} {os.path.basename(f):12s} potong awal {head:.2f} s, akhir {tail:.2f} s -> {d:5.2f} s')
    files = {}
    for name in NAMES:
        p = os.path.join(OUT, name + '.mp3')
        if os.path.exists(p):
            data = open(p, 'rb').read()
            files[name] = {'size': len(data), 'sha1': hashlib.sha1(data).hexdigest()}
    with open(os.path.join(OUT, 'index.json'), 'w') as w:
        json.dump({'files': files}, w, indent=1)
        w.write('\n')
    missing = [n for n in NAMES if n not in files]
    print(f'{len(files)} file, {sum(v["size"] for v in files.values()) / 1e6:.1f} MB'
          + (' · belum ada: ' + ', '.join(missing) if missing else ''))


if __name__ == '__main__':
    main()
