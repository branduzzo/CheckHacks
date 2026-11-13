#!/usr/bin/env bash
# Build Veneer, start the local test server in run-server/ with it, connect the test bot, stop the server.
# Usage: tools/run-test.sh [bot options...]   e.g. tools/run-test.sh --brand fabric --name Bot1
# Needs: Java 21+, Maven, Node.js, run-server/paper.jar (offline mode, eula accepted).
set -euo pipefail
cd "$(dirname "$0")/.."
SERVER=run-server
PORT=$(grep -oP '^server-port=\K[0-9]+' "$SERVER/server.properties" 2>/dev/null || echo 25565)

[ -f "$SERVER/paper.jar" ] || { echo "No $SERVER/paper.jar: put a Paper or Folia jar there first" >&2; exit 1; }
[ "${SKIP_BUILD:-0}" = 1 ] || mvn -B -q clean package -DskipTests
JAR=$(ls veneer-plugin/target/veneer-*.jar | grep -v -- '-lite' | head -1)
mkdir -p "$SERVER/plugins"
rm -f "$SERVER"/plugins/veneer*.jar
cp "$JAR" "$SERVER/plugins/veneer.jar"

[ -d tools/test-bot/node_modules ] || (cd tools/test-bot && npm ci --silent)

LOG=$(mktemp)
(cd "$SERVER" && exec java -Xmx2G -jar paper.jar --nogui) > "$LOG" 2>&1 < /dev/null &
PID=$!
trap 'kill "$PID" 2>/dev/null; wait "$PID" 2>/dev/null; echo "Server log: $LOG"' EXIT

echo "Waiting for the server on port $PORT (pid $PID)..."
for _ in $(seq 1 180); do
  grep -q 'Done (' "$LOG" && break
  kill -0 "$PID" 2>/dev/null || { cat "$LOG"; echo "Server exited" >&2; exit 1; }
  sleep 1
done
grep -q 'Done (' "$LOG" || { echo "Server did not start in 3 minutes" >&2; exit 1; }

timeout "${BOT_SECONDS:-60}" node tools/test-bot/bot.js --port "$PORT" "$@" || true
echo "--- Veneer lines from the server log ---"
grep -i 'veneer' "$LOG" | tail -n 40 || true
