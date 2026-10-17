#!/usr/bin/env bash
# End-to-end check: build the JAR, start it, analyze the sample, generate a project, compile that project.
set -euo pipefail
cd "$(dirname "$0")"
command -v mvn >/dev/null || { echo "Maven (mvn) is required"; exit 1; }
mvn -q -DskipTests package
PORT=${PORT:-18765}
OUT=$(mktemp -d)
java -jar target/specforge.jar --no-browser --port=$PORT --home="$OUT/home" >"$OUT/server.log" 2>&1 &
PID=$!
trap 'kill $PID 2>/dev/null || true' EXIT
for i in $(seq 1 40); do curl -sf -H 'X-SpecForge: 1' localhost:$PORT/api/info >/dev/null && break; sleep 0.5; done
H=(-H 'X-SpecForge: 1' -H 'Content-Type: application/json')
python3 - "$OUT" <<'PY'
import json,sys
json.dump({"mode":"text","content":open("samples/petstore.yaml").read(),"name":"petstore"},open(sys.argv[1]+"/analyze.json","w"))
PY
ID=$(curl -sf "${H[@]}" -d @"$OUT/analyze.json" localhost:$PORT/api/spec/analyze | python3 -c 'import json,sys;print(json.load(sys.stdin)["specId"])')
DEFAULTS=$(curl -sf "${H[@]}" localhost:$PORT/api/info | python3 -c 'import json,sys;d=json.load(sys.stdin)["defaults"];d["targetDir"]=sys.argv[1]+"/gen";print(json.dumps(d))' "$OUT")
curl -sf "${H[@]}" -d "{\"specId\":\"$ID\",\"config\":$DEFAULTS,\"overrides\":{},\"overwrite\":\"SKIP\"}" localhost:$PORT/api/generate/write >/dev/null
echo "Generated into $OUT/gen"; find "$OUT/gen" -type f | sed "s|$OUT/gen/||" | sort
(cd "$OUT/gen" && mvn -q compile) && echo "OK: generated project compiles"
