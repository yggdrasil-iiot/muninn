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
echo "[GATE] step 7: teardown"
[ -n "$SIM_PID" ] && taskkill //F //T //PID "$SIM_PID" >/dev/null 2>&1 || true
kill_by_mainclass "bifrost-sim.jar" || true
kill_by_mainclass "muninn.jar" || true
docker compose -f "$COMPOSE_WIN" stop hivemq-ce >/dev/null 2>&1 || true

echo ""
echo "[GATE] PASS run-muninn-gate.sh"
exit 0
