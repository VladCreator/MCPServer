#!/usr/bin/env bash
# Start, stop, inspect or tail the Appium server used by the iOS UI bridge.
set -euo pipefail

PORT="${APPIUM_PORT:-4723}"
APPIUM_HOME="${APPIUM_HOME:-$HOME/tester-appium}"
LOG_DIR="${APPIUM_LOG_DIR:-$HOME/Library/Logs/Tester}"
PID_FILE="$LOG_DIR/appium-$PORT.pid"
LOG_FILE="$LOG_DIR/appium-$PORT.log"
DEFAULT_XCODE="/Volumes/Mac_Ext/Applications/Xcode.app/Contents/Developer"

mkdir -p "$LOG_DIR"

resolve_xcode() {
  if [[ -n "${DEVELOPER_DIR:-}" && -d "$DEVELOPER_DIR" ]]; then
    printf '%s' "$DEVELOPER_DIR"
  elif [[ -d "$DEFAULT_XCODE" ]]; then
    printf '%s' "$DEFAULT_XCODE"
  else
    xcode-select -p
  fi
}

resolve_appium() {
  local candidates=(
    "$APPIUM_HOME/node_modules/.bin/appium"
    "$HOME/tester-appium/node_modules/.bin/appium"
    "/tmp/appium/node_modules/.bin/appium"
  )
  local candidate
  for candidate in "${candidates[@]}"; do
    if [[ -x "$candidate" ]]; then
      printf '%s' "$candidate"
      return 0
    fi
  done
  if command -v appium >/dev/null 2>&1; then
    command -v appium
    return 0
  fi
  return 1
}

read_pid() {
  if [[ -f "$PID_FILE" ]]; then
    cat "$PID_FILE" 2>/dev/null || true
  fi
}

is_running() {
  local pid
  pid="$(read_pid)"
  [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null
}

status() {
  local pid
  pid="$(read_pid)"
  if is_running; then
    echo "Appium is running (pid $pid) on port $PORT."
    curl -sS --max-time 3 "http://127.0.0.1:$PORT/status" || true
    echo
  else
    echo "Appium is not running on port $PORT."
  fi
}

start() {
  if is_running; then
    echo "Appium is already running (pid $(read_pid)) on port $PORT."
    return 0
  fi
  local appium_bin xcode
  if ! appium_bin="$(resolve_appium)"; then
    cat >&2 <<MSG
Appium is not installed.
Install it with one of:
  npm install -g appium && appium driver install xcuitest
or:
  mkdir -p "$APPIUM_HOME" && cd "$APPIUM_HOME"
  npm init -y && npm install appium@latest && npx appium driver install xcuitest
See docs/APPIUM_SETUP.md.
MSG
    return 1
  fi
  xcode="$(resolve_xcode)"
  local appium_dir="$PWD"
  if [[ "$appium_bin" == */node_modules/.bin/appium ]]; then
    appium_dir="$(cd "$(dirname "$(dirname "$(dirname "$appium_bin")")")" && pwd)"
  fi
  echo "Starting Appium: $appium_bin"
  echo "Appium home: $appium_dir"
  echo "Developer dir: $xcode"
  echo "Log: $LOG_FILE"
  (cd "$appium_dir" && exec env DEVELOPER_DIR="$xcode" "$appium_bin" --port "$PORT" --log-level info) >"$LOG_FILE" 2>&1 &
  echo $! >"$PID_FILE"
  local i
  for i in $(seq 1 30); do
    if curl -sf --max-time 1 "http://127.0.0.1:$PORT/status" >/dev/null 2>&1; then
      echo "Appium is ready at http://127.0.0.1:$PORT"
      return 0
    fi
    sleep 1
  done
  echo "Appium did not become ready. Check $LOG_FILE" >&2
  return 1
}

stop() {
  local pid
  pid="$(read_pid)"
  if [[ -z "$pid" ]] || ! kill -0 "$pid" 2>/dev/null; then
    echo "Appium is not running on port $PORT."
    rm -f "$PID_FILE"
    return 0
  fi
  if ! ps -p "$pid" -o command= 2>/dev/null | grep -q appium; then
    echo "PID $pid does not look like Appium; refusing to kill it." >&2
    return 1
  fi
  kill "$pid" 2>/dev/null || true
  local i
  for i in $(seq 1 20); do
    if ! kill -0 "$pid" 2>/dev/null; then
      rm -f "$PID_FILE"
      echo "Appium stopped."
      return 0
    fi
    sleep 0.5
  done
  echo "Appium did not stop gracefully; sending SIGKILL." >&2
  kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
}

case "${1:-}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) tail -n "${2:-80}" "$LOG_FILE" ;;
  *)
    cat <<MSG
Usage: $0 {start|stop|restart|status|logs [lines]}

Environment:
  APPIUM_PORT      Appium port (default 4723)
  APPIUM_HOME      Appium install directory (default ~/tester-appium)
  APPIUM_LOG_DIR   Log/PID directory (default ~/Library/Logs/Tester)
  DEVELOPER_DIR    Xcode developer directory (auto-detected)
MSG
    exit 2
    ;;
esac
