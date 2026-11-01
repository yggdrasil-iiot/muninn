#!/usr/bin/env bash
# CHUNK-4 ACCEPTANCE GATE: prove muninn stands the whole southbound->northbound spine end-to-end —
# a real MQTT broker + a live embedded OPC-UA sim + bifrost's provenance-publish + the shaded
# muninn feeder/observer. muninn PROVENANCE-VERIFIES a governed def, births it as a Sparkplug B
# NBIRTH carrying the EXACT verified registry bytes, samples the live sim instance over OPC-UA,
# egress-validates each sample, and publishes only conformant members as NDATA.
#
# This exercises separately-built artifacts with ZERO shared code:
#   target/muninn.jar                          — feeder + observer (this repo)
#   ../bifrost/sim/target/bifrost-sim.jar      — the embedded OPC-UA MixerType server + Line1/Mixer1
#   ../bifrost/gates/target/bifrost-gates.jar  — bifrost's provenance ③ publisher (mint the pair)
#   docker-compose.yml (hivemq-ce)             — the MQTT broker
#
# Asserts:
#   HAPPY  : NBIRTH bytes are byte-IDENTICAL to the published registry recipe-setpoints.yaml
#            (the spine assertion), and all 4 conformant members (Rpm/Temp/Running/Secret) reach NDATA.
#   DROP   : --inject-bogus Secret makes egress-validate drop Secret; only Rpm/Temp/Running reach NDATA.
#   TAMPER : appending a byte to a COPY of the published recipe makes muninn REFUSE to birth (exit 1).
#   LOCATION: --hierarchy against a mapping release Bifrost published (the committed fixture
#            src/test/resources/bifrost-mappings, Bifrost's own synthetic plant) keeps the NBIRTH
#            byte-identical and moves the members to DDATA on device synthetic-site:record-line-a.
#   HIER-TAMPER: one changed byte in a COPY of that release's hierarchy file -> REFUSE (exit 1).
#   SESSION: feed --session with --max-cycles: NBIRTH seq 0 bdSeq 0 (byte-identical), NDATA seq
#            1..N, then NDEATH bdSeq 0 and exit 0.
#   REBIRTH: an NCMD Node Control/Rebirth -> a second NBIRTH seq 0 with the same bdSeq, data seq
#            restarts.
#   REBIRTH-TAMPER: the registry changes under a live session -> the rebirth is refused, NDEATH,
#            exit 1, no second NBIRTH.
#   WILL   : the session process is killed -> the broker delivers its Will NDEATH bdSeq 0.
#   HOST   : --primary-host -> no birth until STATE online; an older offline is ignored; offline ->
#            NDEATH; online again -> NBIRTH with bdSeq 1.
#   BROKER-LOSS: the broker stops while a session waits for its host -> the session ends, exit 2.
#
# Run from anywhere (needs Docker Desktop running + host port 1883 free):
#   bash scripts/run-muninn-gate.sh
#   # expect: [GATE] PASS run-muninn-gate.sh ... exit 0
set -euo pipefail
cd "$(dirname "$0")/.."

# cygpath exists only under Git Bash / Cygwin. Elsewhere a POSIX path is already what the JVM
# wants, so shim it to identity (drop the -m, keep the last argument) rather than making every
# call site conditional. Without this this gate only runs on Windows.
command -v cygpath >/dev/null 2>&1 || cygpath() { printf '%s
' "${@: -1}"; }

WORK="build/gate"
SIM_LOG="$WORK/sim.log"
OBS_LOG="$WORK/observe.log"

MUNINN_JAR="target/muninn.jar"
SIM_JAR="../bifrost/sim/target/bifrost-sim.jar"
GATES_JAR="../bifrost/gates/target/bifrost-gates.jar"

ENDPOINT="opc.tcp://localhost:48400"
NS_URI="urn:bifrost:opcua:sim"
INSTANCE="Line1/Mixer1"
MQTT_URL="tcp://localhost:1883"
GROUP="Bifrost:Line1"
EDGE="recipe-edge"
REF="Line1-Mixer"
VERSION="1.0.0"

fail() {
  echo "[GATE] FAIL: $*"
  echo "--- sim log tail ---";     tail -60 "$SIM_LOG" 2>/dev/null || true
  echo "--- observe log tail ---"; tail -60 "$OBS_LOG" 2>/dev/null || true
  exit 1
}

kill_by_mainclass() {  # $1=substring of the jps -lm main-class/jar line
  { jps -lm 2>/dev/null | grep -i "$1" || true; } | awk '{print $1}' | while read -r p; do
    taskkill //F //T //PID "$p" >/dev/null 2>&1 || true
  done
}

SIM_PID=""
OBS_PID=""
COMPOSE_WIN=""
cleanup() {
  [ -n "$SIM_PID" ] && taskkill //F //T //PID "$SIM_PID" >/dev/null 2>&1 || true
  [ -n "$OBS_PID" ] && taskkill //F //T //PID "$OBS_PID" >/dev/null 2>&1 || true
  # `$!` from an MSYS-backgrounded native `java -jar` does not reliably match the real Win32 PID
  # (a known MSYS fork/exec quirk); jps -lm on a `-jar` launch reports the JAR PATH, not the main
  # class, so match on the jar filename instead — the taskkills above are best-effort only.
  kill_by_mainclass "bifrost-sim.jar" || true
  kill_by_mainclass "muninn.jar" || true
  [ -n "$COMPOSE_WIN" ] && docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || true
}
trap cleanup EXIT

# ---------------------------------------------------------------------------
echo "[GATE] step 0: preflight + build"
command -v docker >/dev/null 2>&1 || { echo "[GATE] FAIL: docker not found on PATH — Docker Desktop required for the MQTT broker"; exit 1; }

mvn -q package
[ -f "$MUNINN_JAR" ] || fail "$MUNINN_JAR missing after build"

if [ ! -f "$SIM_JAR" ] || [ ! -f "$GATES_JAR" ]; then
  ( cd ../bifrost && mvn -q -pl core,sim,gates install )
fi
[ -f "$SIM_JAR" ]   || fail "$SIM_JAR missing after build"
[ -f "$GATES_JAR" ] || fail "$GATES_JAR missing after build"

# Mop up orphans + any foreign broker holding host :1883 BEFORE starting anything new.
kill_by_mainclass "bifrost-sim.jar" || true
kill_by_mainclass "muninn.jar" || true
docker rm -f bifrost-hivemq-ce muninn-hivemq-ce >/dev/null 2>&1 || true

MUNINN_JAR_WIN="$(cygpath -m "$(pwd)/$MUNINN_JAR")"
SIM_JAR_WIN="$(cygpath -m "$(pwd)/$SIM_JAR")"
GATES_JAR_WIN="$(cygpath -m "$(pwd)/$GATES_JAR")"
COMPOSE_WIN="$(cygpath -m "$(pwd)/docker-compose.yml")"
echo "[GATE] muninn=$MUNINN_JAR_WIN"
echo "[GATE] sim=$SIM_JAR_WIN"
echo "[GATE] gates=$GATES_JAR_WIN"

# Fresh workdir FIRST so sim.log/registry/srcrepo/out all have a home.
rm -rf "$WORK"
mkdir -p "$WORK/registry" "$WORK/srcrepo" "$WORK/out"

REGISTRY_WIN="$(cygpath -m "$(pwd)/$WORK/registry")"
SRCREPO_WIN="$(cygpath -m "$(pwd)/$WORK/srcrepo")"
REGISTRY_TAMPERED_WIN="$(cygpath -m "$(pwd)/$WORK/registry-tampered")"

# ---------------------------------------------------------------------------
echo "[GATE] step 1: start HiveMQ CE (broker) + wait for :1883"
docker compose -f "$COMPOSE_WIN" up -d hivemq-ce >/dev/null 2>&1 || fail "failed to start hivemq-ce"
ok=0
for i in $(seq 1 30); do
  bash -c "echo > /dev/tcp/localhost/1883" >/dev/null 2>&1 && { ok=1; break; }
  sleep 2
done
if [ "$ok" != "1" ]; then
  docker compose -f "$COMPOSE_WIN" logs --tail 40 hivemq-ce 2>/dev/null || true
  fail "HiveMQ CE did not open :1883"
fi
echo "[GATE] HiveMQ CE up on :1883"

# ---------------------------------------------------------------------------
echo "[GATE] step 2: start the embedded OPC-UA sim + wait for 'OPC-UA sim listening'"
: > "$SIM_LOG"
java -jar "$SIM_JAR_WIN" > "$SIM_LOG" 2>&1 &
SIM_PID=$!
ok=0
for i in $(seq 1 30); do
  grep -q "OPC-UA sim listening" "$SIM_LOG" 2>/dev/null && { ok=1; break; }
  sleep 1
done
[ "$ok" = "1" ] || fail "OPC-UA sim did not start (pid $SIM_PID)"
echo "[GATE] OPC-UA sim listening (pid $SIM_PID)"

# ---------------------------------------------------------------------------
echo "[GATE] step 3: mint the provenance pair (bifrost provenance publish)"
cp scripts/fixtures/Line1-Mixer-1.0.0.json "$WORK/srcrepo/"
git -C "$SRCREPO_WIN" init -q
git -C "$SRCREPO_WIN" config user.email gate@local
git -C "$SRCREPO_WIN" config user.name gate
git -C "$SRCREPO_WIN" add Line1-Mixer-1.0.0.json
git -C "$SRCREPO_WIN" commit -qm seed

set +e
java -jar "$GATES_JAR_WIN" provenance publish "$REGISTRY_WIN" "$SRCREPO_WIN" Line1-Mixer-1.0.0.json "$REF" "$VERSION"
code=$?
set -e
[ "$code" -eq 0 ] || fail "provenance publish returned $code — expected 0"
CANON="$WORK/registry/recipe/$REF/$VERSION/recipe-setpoints.yaml"
[ -f "$CANON" ] || fail "provenance publish did not write $CANON"
[ -f "$WORK/registry/recipe/$REF/$VERSION/manifest.json" ] || fail "provenance publish did not write manifest.json"
echo "[GATE] provenance pair minted at recipe/$REF/$VERSION/"

# ---------------------------------------------------------------------------
# Poll two capture files until non-empty (an MSYS-safe substitute for `wait $OBS_PID` on a
# backgrounded native JVM whose $! may not be the real Win32 PID).
poll_ndata() {  # $1=ndata file  $2=birth file  $3=label
  local t=0
  while [ "$t" -lt 40 ]; do
    if [ -s "$1" ] && [ -s "$2" ]; then return 0; fi
    sleep 0.5; t=$((t + 1))
  done
  return 1
}

wait_observer_ready() {  # waits for '[OBSERVE] subscribed' in $OBS_LOG
  local t=0
  while [ "$t" -lt 20 ]; do
    grep -q "\[OBSERVE\] subscribed" "$OBS_LOG" 2>/dev/null && return 0
    sleep 0.5; t=$((t + 1))
  done
  return 1
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== HAPPY: byte-exact NBIRTH + all 4 members reach NDATA ====="
BIRTH_WIN="$(cygpath -m "$(pwd)/$WORK/out/birth.bin")"
NDATA_WIN="$(cygpath -m "$(pwd)/$WORK/out/ndata.txt")"
: > "$OBS_LOG"
java -jar "$MUNINN_JAR_WIN" observe "$MQTT_URL" "$GROUP" "$BIRTH_WIN" "$NDATA_WIN" \
  --expect-ndata 4 --timeout-ms 20000 > "$OBS_LOG" 2>&1 &
OBS_PID=$!
wait_observer_ready || fail "observer never printed '[OBSERVE] subscribed'"

set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE"
code=$?
set -e
[ "$code" -eq 0 ] || fail "feed (happy) returned $code — expected 0"

poll_ndata "$WORK/out/ndata.txt" "$WORK/out/birth.bin" happy || fail "happy: observer never captured birth+ndata"
OBS_PID=""

cmp -s "$WORK/out/birth.bin" "$CANON" \
  || fail "NBIRTH bytes NOT byte-identical to published recipe-setpoints.yaml (spine assertion)"
for mem in Rpm Temp Running Secret; do
  grep -q "^$mem$" "$WORK/out/ndata.txt" || fail "happy: NDATA missing member $mem"
done
echo "[GATE] HAPPY OK: NBIRTH byte-identical; NDATA carried Rpm/Temp/Running/Secret"

# ---------------------------------------------------------------------------
echo "[GATE] ===== DROP: --inject-bogus Secret -> Secret dropped, only 3 members reach NDATA ====="
BIRTH_DROP_WIN="$(cygpath -m "$(pwd)/$WORK/out/birth-drop.bin")"
NDATA_DROP_WIN="$(cygpath -m "$(pwd)/$WORK/out/ndata-drop.txt")"
: > "$OBS_LOG"
java -jar "$MUNINN_JAR_WIN" observe "$MQTT_URL" "$GROUP" "$BIRTH_DROP_WIN" "$NDATA_DROP_WIN" \
  --expect-ndata 3 --timeout-ms 20000 > "$OBS_LOG" 2>&1 &
OBS_PID=$!
wait_observer_ready || fail "drop: observer never printed '[OBSERVE] subscribed'"

set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE" --inject-bogus Secret
code=$?
set -e
[ "$code" -eq 0 ] || fail "feed (drop) returned $code — expected 0"

poll_ndata "$WORK/out/ndata-drop.txt" "$WORK/out/birth-drop.bin" drop || fail "drop: observer never captured birth+ndata"
OBS_PID=""

grep -q "^Rpm$" "$WORK/out/ndata-drop.txt"      || fail "drop: NDATA missing Rpm"
grep -q "^Temp$" "$WORK/out/ndata-drop.txt"     || fail "drop: NDATA missing Temp"
grep -q "^Running$" "$WORK/out/ndata-drop.txt"  || fail "drop: NDATA missing Running"
grep -q "^Secret$" "$WORK/out/ndata-drop.txt"   && fail "drop: Secret reached NDATA — inject-bogus did NOT drop it"
echo "[GATE] DROP OK: Rpm/Temp/Running birthed, Secret dropped by egress-validate"

# ---------------------------------------------------------------------------
echo "[GATE] ===== TAMPER: appended byte in registry copy -> muninn REFUSES to birth (exit 1) ====="
cp -r "$WORK/registry" "$WORK/registry-tampered"
printf 'X' >> "$WORK/registry-tampered/recipe/$REF/$VERSION/recipe-setpoints.yaml"

set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_TAMPERED_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE"
code=$?
set -e
[ "$code" -eq 1 ] || fail "feed (tamper) returned $code — expected 1 (provenance refuse before MQTT connect)"
echo "[GATE] TAMPER OK: provenance mismatch refused (exit 1), no birth"

# ---------------------------------------------------------------------------
echo "[GATE] ===== LOCATION: --hierarchy -> NBIRTH unchanged, members on device synthetic-site:record-line-a ====="
MAPPINGS_WIN="$(cygpath -m "$(pwd)/src/test/resources/bifrost-mappings")"
BIRTH_LOC_WIN="$(cygpath -m "$(pwd)/$WORK/out/birth-loc.bin")"
NDATA_LOC_WIN="$(cygpath -m "$(pwd)/$WORK/out/ndata-loc.txt")"
: > "$OBS_LOG"
java -jar "$MUNINN_JAR_WIN" observe "$MQTT_URL" "$GROUP" "$BIRTH_LOC_WIN" "$NDATA_LOC_WIN" \
  --expect-ndata 4 --timeout-ms 20000 > "$OBS_LOG" 2>&1 &
OBS_PID=$!
wait_observer_ready || fail "location: observer never printed '[OBSERVE] subscribed'"

set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE" --hierarchy "$MAPPINGS_WIN" hierarchy-line record-line-a
code=$?
set -e
[ "$code" -eq 0 ] || fail "feed (location) returned $code — expected 0"

poll_ndata "$WORK/out/ndata-loc.txt" "$WORK/out/birth-loc.bin" location || fail "location: observer never captured birth+ddata"
OBS_PID=""

cmp -s "$WORK/out/birth-loc.bin" "$CANON" || fail "location: NBIRTH bytes changed under --hierarchy"
for mem in Rpm Temp Running Secret; do
  grep -q "^$mem$" "$WORK/out/ndata-loc.txt" || fail "location: DDATA missing member $mem"
done
grep -q "\[OBSERVE\] device synthetic-site:record-line-a$" "$OBS_LOG" \
  || fail "location: no DDATA on device synthetic-site:record-line-a"
echo "[GATE] LOCATION OK: NBIRTH byte-identical; DDATA on device synthetic-site:record-line-a"

# ---------------------------------------------------------------------------
echo "[GATE] ===== HIER-TAMPER: changed byte in the release's hierarchy file -> REFUSE (exit 1) ====="
cp -r src/test/resources/bifrost-mappings "$WORK/mappings-tampered"
sed -i 's/"Line A"/"Line X"/' "$WORK/mappings-tampered/releases/release/1.0.0/payload/hierarchy/0.json"
cmp -s "$WORK/mappings-tampered/releases/release/1.0.0/payload/hierarchy/0.json" \
  src/test/resources/bifrost-mappings/releases/release/1.0.0/payload/hierarchy/0.json \
  && fail "hier-tamper: the edit did not change the hierarchy file"

set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE" --hierarchy "$(cygpath -m "$(pwd)/$WORK/mappings-tampered")" hierarchy-line record-line-a
code=$?
set -e
[ "$code" -eq 1 ] || fail "feed (hier-tamper) returned $code — expected 1 (refuse before MQTT connect)"
echo "[GATE] HIER-TAMPER OK: hierarchy digest mismatch refused (exit 1), no birth"

# ---------------------------------------------------------------------------
# Session cases (feed --session). The observer logs one '[OBSERVE] <type> seq=.. bdSeq=..' line per
# message; each case starts its own observer and kills it afterwards.
start_session_observer() {  # $1=label
  : > "$OBS_LOG"
  java -jar "$MUNINN_JAR_WIN" observe "$MQTT_URL" "$GROUP" \
    "$(cygpath -m "$(pwd)/$WORK/out/birth-$1.bin")" "$(cygpath -m "$(pwd)/$WORK/out/ndata-$1.txt")" \
    --expect-ndata 1000000 --timeout-ms 120000 > "$OBS_LOG" 2>&1 &
  wait_observer_ready || fail "$1: observer never printed '[OBSERVE] subscribed'"
}

wait_log() {  # $1=extended regex over the observer log  $2=what  — up to 20 s
  local t=0
  while [ "$t" -lt 40 ]; do
    grep -Eq "$1" "$OBS_LOG" 2>/dev/null && return 0
    sleep 0.5; t=$((t + 1))
  done
  fail "$2 (no line matching '$1')"
}

count_log() {  # $1=extended regex
  grep -Ec "$1" "$OBS_LOG" 2>/dev/null || true
}

session_feed() {  # $1=registry dir  $@=extra flags; runs in the background, sets FEED_JOB
  local reg="$1"; shift
  java -jar "$MUNINN_JAR_WIN" feed "$reg" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
    "$MQTT_URL" "$GROUP" "$EDGE" "$@" > "$WORK/out/feed.log" 2>&1 &
  FEED_JOB=$!
}

host() {
  java -jar "$MUNINN_JAR_WIN" host "$MQTT_URL" "$@" >/dev/null || fail "host $* failed"
}

# ---------------------------------------------------------------------------
echo "[GATE] ===== SESSION: --session 300 --max-cycles 4 -> NBIRTH seq 0, NDATA seq 1..4, NDEATH ====="
start_session_observer session
set +e
java -jar "$MUNINN_JAR_WIN" feed "$REGISTRY_WIN" "$REF" "$VERSION" "$ENDPOINT" "$NS_URI" "$INSTANCE" \
  "$MQTT_URL" "$GROUP" "$EDGE" --session 300 --max-cycles 4
code=$?
set -e
[ "$code" -eq 0 ] || fail "session returned $code — expected 0"
wait_log "^\[OBSERVE\] NDEATH bdSeq=0$" "session: no NDEATH bdSeq=0 after the last cycle"
grep -q "^\[OBSERVE\] NBIRTH seq=0 bdSeq=0$" "$OBS_LOG" || fail "session: no NBIRTH seq=0 bdSeq=0"
for n in 1 2 3 4; do
  grep -q "^\[OBSERVE\] NDATA seq=$n$" "$OBS_LOG" || fail "session: no NDATA seq=$n"
done
[ "$(count_log '^\[OBSERVE\] NDATA')" -eq 4 ] || fail "session: expected exactly 4 NDATA"
cmp -s "$WORK/out/birth-session.bin" "$CANON" || fail "session: NBIRTH bytes not byte-identical to the registry"
kill_by_mainclass "muninn.jar observe"
echo "[GATE] SESSION OK: NBIRTH seq 0 bdSeq 0 byte-identical; NDATA seq 1..4; NDEATH bdSeq 0; exit 0"

# ---------------------------------------------------------------------------
echo "[GATE] ===== REBIRTH: NCMD rebirth -> verified again, NBIRTH seq 0 with the same bdSeq ====="
start_session_observer rebirth
session_feed "$REGISTRY_WIN" --session 300 --max-cycles 15
wait_log "^\[OBSERVE\] NDATA seq=2$" "rebirth: session never reached NDATA seq=2"
host rebirth "$GROUP" "$EDGE"
wait_log "^\[OBSERVE\] NDEATH bdSeq=0$" "rebirth: session did not end"
set +e; wait "$FEED_JOB"; code=$?; set -e
[ "$code" -eq 0 ] || fail "rebirth: session returned $code — expected 0"
[ "$(count_log '^\[OBSERVE\] NBIRTH seq=0 bdSeq=0$')" -eq 2 ] || fail "rebirth: expected two NBIRTH seq=0 bdSeq=0"
awk '/NBIRTH/{b++} b==2 && /NDATA seq=1$/{ok=1} END{exit !ok}' "$OBS_LOG" \
  || fail "rebirth: seq did not restart at 1 after the second NBIRTH"
cmp -s "$WORK/out/birth-rebirth.bin" "$CANON" || fail "rebirth: second NBIRTH not byte-identical"
kill_by_mainclass "muninn.jar observe"
echo "[GATE] REBIRTH OK: second NBIRTH seq 0 bdSeq 0 byte-identical; data seq restarted"

# ---------------------------------------------------------------------------
echo "[GATE] ===== REBIRTH-TAMPER: registry changed under a live session -> rebirth refused, NDEATH, exit 1 ====="
rm -rf "$WORK/registry-live"
cp -r "$WORK/registry" "$WORK/registry-live"
start_session_observer rebirth-tamper
session_feed "$(cygpath -m "$(pwd)/$WORK/registry-live")" --session 300
wait_log "^\[OBSERVE\] NDATA seq=2$" "rebirth-tamper: session never reached NDATA seq=2"
printf 'X' >> "$WORK/registry-live/recipe/$REF/$VERSION/recipe-setpoints.yaml"
host rebirth "$GROUP" "$EDGE"
wait_log "^\[OBSERVE\] NDEATH bdSeq=0$" "rebirth-tamper: no NDEATH after the refused rebirth"
set +e; wait "$FEED_JOB"; code=$?; set -e
[ "$code" -eq 1 ] || fail "rebirth-tamper: session returned $code — expected 1"
[ "$(count_log '^\[OBSERVE\] NBIRTH')" -eq 1 ] || fail "rebirth-tamper: a second NBIRTH was published"
grep -q "\[MUNINN\] refuse" "$WORK/out/feed.log" || fail "rebirth-tamper: no refusal logged"
kill_by_mainclass "muninn.jar observe"
echo "[GATE] REBIRTH-TAMPER OK: rebirth refused, NDEATH bdSeq 0, exit 1"

# ---------------------------------------------------------------------------
echo "[GATE] ===== WILL: session killed without NDEATH -> broker delivers the Will NDEATH ====="
start_session_observer will
session_feed "$REGISTRY_WIN" --session 300
wait_log "^\[OBSERVE\] NDATA seq=2$" "will: session never reached NDATA seq=2"
kill_by_mainclass "muninn.jar feed"
wait_log "^\[OBSERVE\] NDEATH bdSeq=0$" "will: broker did not deliver the Will NDEATH"
kill_by_mainclass "muninn.jar observe"
echo "[GATE] WILL OK: forced kill -> Will NDEATH bdSeq 0 from the broker"

# ---------------------------------------------------------------------------
echo "[GATE] ===== HOST: --primary-host -> birth waits for online; offline -> NDEATH, next bdSeq ====="
HOST_ID="gate-host-$$-$RANDOM"
start_session_observer host
session_feed "$REGISTRY_WIN" --session 300 --primary-host "$HOST_ID"
sleep 3
[ "$(count_log '^\[OBSERVE\] (NBIRTH|NDATA)')" -eq 0 ] || fail "host: birth or data before the host was online"
host state "$HOST_ID" true 1000
wait_log "^\[OBSERVE\] NDATA seq=1$" "host: no birth and data after STATE online"
grep -q "^\[OBSERVE\] NBIRTH seq=0 bdSeq=0$" "$OBS_LOG" || fail "host: first NBIRTH not bdSeq=0"
host state "$HOST_ID" false 500
sleep 1
[ "$(count_log '^\[OBSERVE\] NDEATH')" -eq 0 ] || fail "host: an older offline STATE ended the session"
host state "$HOST_ID" false 1000
wait_log "^\[OBSERVE\] NDEATH bdSeq=0$" "host: no NDEATH after STATE offline"
host state "$HOST_ID" true 2000
wait_log "^\[OBSERVE\] NBIRTH seq=0 bdSeq=1$" "host: no NBIRTH with the next bdSeq after STATE online"
kill_by_mainclass "muninn.jar feed"
wait_log "^\[OBSERVE\] NDEATH bdSeq=1$" "host: Will of the second session did not carry bdSeq=1"
kill_by_mainclass "muninn.jar observe"
echo "[GATE] HOST OK: birth waited for online; stale offline ignored; offline -> NDEATH 0; online -> NBIRTH bdSeq 1"

# ---------------------------------------------------------------------------
# Last on purpose: it stops the broker.
echo "[GATE] ===== BROKER-LOSS: broker stops while the session waits for its host -> exit 2 ====="
session_feed "$REGISTRY_WIN" --session 300 --primary-host "gate-absent-host-$$-$RANDOM"
t=0
until grep -q "\[MUNINN\] session up" "$WORK/out/feed.log" 2>/dev/null; do
  sleep 0.5; t=$((t + 1)); [ "$t" -lt 40 ] || fail "broker-loss: session never came up"
done
docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || fail "broker-loss: could not stop the broker"
set +e; wait "$FEED_JOB"; code=$?; set -e
[ "$code" -eq 2 ] || fail "broker-loss: session returned $code — expected 2"
grep -q "broker connection lost" "$WORK/out/feed.log" || fail "broker-loss: no 'broker connection lost' in the feed log"
echo "[GATE] BROKER-LOSS OK: lost broker connection while waiting for the host ended the session (exit 2)"

# ---------------------------------------------------------------------------
echo "[GATE] step 7: teardown"
[ -n "$SIM_PID" ] && taskkill //F //T //PID "$SIM_PID" >/dev/null 2>&1 || true
kill_by_mainclass "bifrost-sim.jar" || true
kill_by_mainclass "muninn.jar" || true
docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || true

echo ""
echo "[GATE] PASS run-muninn-gate.sh"
exit 0
