# Bangun res/drawable/qr_*.xml: QR code (vector drawable) untuk HelpVideo (video tutorial saat ADB gagal) dan
# Pengaturan › KlikBeam (ikut uji tertutup). Dibuat sekali di sini supaya aplikasi tidak butuh pembuat QR:
#   python3 -I tools/build_qr.py      (butuh: pip install segno)
# URL di sini harus sama dengan HelpVideo.URL.
import segno

VIDEOS = {
    'qr_usb': 'https://youtu.be/eVMA64kG_H8',
    'qr_usb_allow': 'https://youtu.be/eVMA64kG_H8?t=62',
    'qr_wireless': 'https://youtu.be/qDb4Irg5wok',
    # Pengaturan › KlikBeam › Android: ikut uji tertutup
    'qr_beam_group': 'https://groups.google.com/g/klikbeam-testers',
    'qr_beam_testing': 'https://play.google.com/apps/testing/com.ashafizullah.klikbeam',
}
QUIET = 2  # tepi putih (modul); kartu putih di HelpVideo menambah tepinya sendiri

for name, url in VIDEOS.items():
    rows = list(segno.make_qr(url, error='m').matrix)
    n = len(rows) + 2 * QUIET
    path = []
    for y, row in enumerate(rows):
        x = 0
        while x < len(row):
            if row[x]:
                start = x
                while x < len(row) and row[x]:
                    x += 1
                path.append('M%d,%dh%dv1h-%dz' % (start + QUIET, y + QUIET, x - start, x - start))
            else:
                x += 1
    with open('res/drawable/%s.xml' % name, 'w') as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n'
                '<!-- %s (tools/build_qr.py) -->\n'
                '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
                '    android:width="%ddp" android:height="%ddp"\n'
                '    android:viewportWidth="%d" android:viewportHeight="%d">\n'
                '    <path android:fillColor="#FFFFFFFF" android:pathData="M0,0h%dv%dh-%dz" />\n'
                '    <path android:fillColor="#FF000000" android:pathData="%s" />\n'
                '</vector>\n' % (url, n * 4, n * 4, n, n, n, n, n, ''.join(path)))
    print(name, url, '%dx%d modul' % (len(rows), len(rows)))
