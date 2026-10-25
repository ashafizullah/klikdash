# Bangun assets/quran.txt dari JSON equran.id: python3 -I tools/build_quran.py ../quran-data/text assets/quran.txt
# Terjemahan English (Saheeh International) langsung dari alquran.cloud, format sama dengan QuranData.downloadEn:
#   python3 -I tools/build_quran.py --en assets/quran-en.txt
import json, sys, re, collections, urllib.request
src, out = sys.argv[1], sys.argv[2]
total = 0; chars = collections.Counter(); bad = []
def clean(s): return re.sub(r'\s+', ' ', re.sub(r'[\x00-\x08\x0b-\x1f\x7f-\x9f]', '', s.replace('\t',' '))).strip()
# Tanda waqaf (U+06D6..U+06DC) ditempel ke kata sebelumnya agar tidak terpisah ke baris sendiri.
# Penanda khas mushaf Kemenag yang tidak ada di font Amiri dan tidak dibaca:
#   U+08D6 (ain kecil, tanda ruku'), U+06D4 (pemisah akhir juz) -> dihapus;
#   U+06D5 dipakai sebagai tumpuan tanda waqaf -> dihapus, tanda waqafnya tetap.
def arab(s):
    s = re.sub(r'[\u08D6\u06D4\u06D5]', '', clean(s))
    s = re.sub(r'\s+([\u06D6-\u06DC])', r'\1', s)
    return re.sub(r'\s+', ' ', s).strip()
COUNTS = [7, 286, 200, 176, 120, 165, 206, 75, 129, 109, 123, 111, 43, 52, 99, 128, 111, 110, 98, 135, 112, 78, 118, 64, 77,
          227, 93, 88, 69, 60, 34, 30, 73, 54, 45, 83, 182, 88, 75, 85, 54, 53, 89, 59, 37, 35, 38, 29, 18, 45, 60, 49, 62, 55,
          78, 96, 29, 22, 24, 13, 14, 11, 11, 18, 12, 12, 30, 52, 52, 44, 28, 28, 20, 56, 40, 31, 50, 40, 46, 42, 29, 19, 36,
          25, 22, 17, 19, 26, 30, 20, 15, 21, 11, 8, 8, 19, 5, 8, 8, 11, 11, 8, 3, 9, 5, 4, 7, 3, 6, 3, 5, 4, 5, 6]
if src == '--en':
    with open(out + '.part', 'w', encoding='utf-8') as f:
        for n in range(1, 115):
            req = urllib.request.Request(f'https://api.alquran.cloud/v1/surah/{n}/en.sahih', headers={'User-Agent': 'KlikDash'})
            d = json.load(urllib.request.urlopen(req, timeout=30))['data']
            if len(d['ayahs']) != COUNTS[n - 1]: sys.exit(f'jumlah ayat surah {n} tidak cocok')
            f.write('\t'.join(['S', str(n), clean(d['englishNameTranslation']), clean(d['revelationType'])]) + '\n')
            for a in d['ayahs']:
                total += 1
                f.write('\t'.join(['A', str(n), str(a['numberInSurah']), clean(a['text'])]) + '\n')
    import os; os.replace(out + '.part', out)
    print('ayat', total)
    sys.exit()
with open(out, 'w', encoding='utf-8') as f:
    for n in range(1, 115):
        d = json.load(open(f'{src}/{n}.json', encoding='utf-8'))['data']
        f.write('\t'.join(['S', str(d['nomor']), clean(d['namaLatin']), clean(d['nama']), clean(d['arti']), clean(d['tempatTurun']), str(d['jumlahAyat'])]) + '\n')
        if len(d['ayat']) != d['jumlahAyat']: bad.append(n)
        for a in d['ayat']:
            total += 1
            for c in a['teksIndonesia']:
                if not c.isascii(): chars[c] += 1
            f.write('\t'.join(['A', str(n), str(a['nomorAyat']), arab(a['teksArab']), clean(a['teksLatin']), clean(a['teksIndonesia'])]) + '\n')
print('ayat', total, 'mismatch', bad)
print('non-ascii in indo:', chars.most_common(25))
