#!/usr/bin/env bash
set -euo pipefail

script_path="$(readlink -f -- "${BASH_SOURCE[0]}")"
script_dir="$(cd -- "$(dirname -- "$script_path")" && pwd)"
runtime_root="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/assistant-desktop-dictation"
state_root="${XDG_STATE_HOME:-$HOME/.local/state}/assistant-desktop-dictation"
queue_dir="$state_root/queue"
pid_file="$runtime_root/recorder.pid"
current_file="$runtime_root/current-audio-path"
log_file="$state_root/dictation.log"
toggle_lock="$runtime_root/toggle.lock"

mkdir -p "$queue_dir" "$runtime_root"
exec 9>"$toggle_lock"
flock -x 9

log() {
    printf '%s - %s\n' "$(date '+%Y-%m-%d %H:%M:%S.%3N')" "$*" >>"$log_file"
}

start_worker() {
    nohup "$script_dir/queue-worker.sh" >>"$log_file" 2>&1 9>&- &
}

stop_recording() {
    local pid audio_file
    pid="$(<"$pid_file")"
    audio_file="$(<"$current_file")"
    /usr/bin/rm -f "$pid_file" "$current_file"

    if kill -0 "$pid" 2>/dev/null; then
        log "Stopping recording pid=$pid file=$audio_file"
        kill -INT "$pid"
        for _ in {1..40}; do
            kill -0 "$pid" 2>/dev/null || break
            sleep 0.05
        done
        if kill -0 "$pid" 2>/dev/null; then
            log "Recorder did not stop after 2 seconds; terminating pid=$pid"
            kill -TERM "$pid" 2>/dev/null || true
        fi
    fi

    if [[ -s "$audio_file" ]]; then
        log "Queued completed recording file=$audio_file bytes=$(stat -c %s "$audio_file")"
        start_worker
    else
        log "Discarding empty recording file=$audio_file"
        /usr/bin/rm -f "$audio_file"
    fi
}

start_recording() {
    local audio_file pid
    audio_file="$queue_dir/recording-$(date '+%Y%m%d-%H%M%S-%N').wav"

    if command -v pw-record >/dev/null 2>&1; then
        pw-record --container wav --format s16 --rate 16000 --channels 1 "$audio_file" \
            >/dev/null 2>&1 9>&- &
    elif command -v arecord >/dev/null 2>&1; then
        arecord -f S16_LE -r 16000 -c 1 -D default "$audio_file" >/dev/null 2>&1 9>&- &
    elif command -v parec >/dev/null 2>&1; then
        parec --file-format=wav --format=s16le --rate=16000 --channels=1 \
            --device=@DEFAULT_SOURCE@ "$audio_file" >/dev/null 2>&1 9>&- &
    else
        log "ERROR: no supported recorder found (pw-record, arecord, or parec)"
        exit 1
    fi
    pid=$!
    printf '%s\n' "$pid" >"$pid_file"
    printf '%s\n' "$audio_file" >"$current_file"
    log "Started recording pid=$pid file=$audio_file"
    start_worker
}

if [[ -f "$pid_file" && -f "$current_file" ]]; then
    stop_recording
else
    /usr/bin/rm -f "$pid_file" "$current_file"
    start_recording
fi
