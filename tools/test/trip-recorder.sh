#!/bin/bash
# Uji TripRecorder di JVM, lalu cek rute di antrean dengan dekoder server KlikBeam (bila ada di ~/Projects/klikbeam).
set -euo pipefail
cd "$(dirname "$0")/../.."
out=$(mktemp -d)
javac -encoding UTF-8 -d "$out" src/com/ashafizullah/klikdash/TripRecorder.java tools/test/TripRecorderTest.java
log=$(java -cp "$out" com.ashafizullah.klikdash.TripRecorderTest) || { echo "$log"; exit 1; }
echo "$log"
server=${KLIKBEAM_SERVER:-$HOME/Projects/klikbeam/server}
if [ -d "$server" ]; then
    q=$(echo "$log" | sed -n 's/^antrean: //p')
    (cd "$server" && bun -e '
        import { decode } from "./src/lib/polyline.ts";
        import { readdirSync, readFileSync } from "fs";
        const dir = process.argv[1];
        for (const f of readdirSync(dir).sort()) {
          const t = JSON.parse(readFileSync(`${dir}/${f}`, "utf8"));
          const p = decode(t.polyline);
          if (!p || p.length < 2) { console.log("GAGAL polyline", f); process.exit(1); }
          console.log(`ok    server membaca ${f.slice(0, 13)}: ${p.length} titik, ${t.distanceM} m`);
        }' "$q")
fi
