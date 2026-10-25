// Bangun assets/dzikir.json dari data dzikir pagi & petang KlikBand (satu sumber untuk kedua aplikasi), dengan
// arti & catatan Indonesia dan English (*_en, dipakai saat bahasa antarmuka English):
//   node tools/build_dzikir.mjs ../../klikband/src/common/dhikrmorningevening.js assets/dzikir.json
import { pathToFileURL } from 'node:url'
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

const [src, out] = process.argv.slice(2)
const { morningEveningDhikr } = await import(pathToFileURL(resolve(src)).href)
// Judul pendek untuk daftar: nama surat/bacaan yang dikenal, selain itu potongan pertama bacaannya.
function title(d, en) {
  const m = d.meaning.id
  if (/Al-Baqarah: 255/.test(m)) return en ? 'Ayat al-Kursi' : 'Ayat Kursi'
  const surat = m.match(/^Baca surat (.+)$/)
  if (surat) return (en ? 'Surah ' : 'Surat ') + surat[1]
  if (/^Sayyidul Istighfar/.test(m)) return en ? 'Sayyid al-Istighfar' : 'Sayyidul Istighfar'
  const words = d.latin.split(/[,.]/)[0].trim().split(/\s+/)
  return words.length > 5 ? words.slice(0, 5).join(' ') + '…' : words.join(' ')
}
const pick = (period) => morningEveningDhikr(period).map((d) => {
  if (!d.meaning.en || (d.note && !d.note.en)) throw new Error('Terjemahan English kosong: ' + d.latin.slice(0, 40))
  return {
    title: title(d, false), title_en: title(d, true), arabic: d.arabic, latin: d.latin, count: d.count,
    meaning: d.meaning.id, meaning_en: d.meaning.en, note: d.note ? d.note.id : null, note_en: d.note ? d.note.en : null,
  }
})
const data = {
  source: 'KlikBand (github.com/ashafizullah/klikband): urutan & jumlah dari almanhaj.or.id/11518-dzikir-pagi-dan-petang.html, Latin mengikuti Hisnul Muslim, teks Al-Qur\'an Tanzil Quran Text (Simple Plain, Version 1.1) (CC BY 3.0, tanzil.net)',
  pagi: pick('morning'),
  petang: pick('evening'),
}
writeFileSync(out, JSON.stringify(data, null, 1) + '\n')
console.log(`pagi ${data.pagi.length} bacaan, petang ${data.petang.length} bacaan -> ${out}`)
