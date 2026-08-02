#!/usr/bin/env bash
set -euo pipefail

script_path="$(readlink -f -- "${BASH_SOURCE[0]}")"
script_dir="$(cd -- "$(dirname -- "$script_path")" && pwd)"
runtime_root="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/assistant-desktop-dictation"
state_root="${XDG_STATE_HOME:-$HOME/.local/state}/assistant-desktop-dictation"
queue_dir="$state_root/queue"
pid_file="$runtime_root/recorder.pid"
current_file="$runtime_root/current-audio-path"
recording_started_file="$runtime_root/current-recording-started-ms"
recorder_backend_file="$runtime_root/recorder-backend"
recorder_health_pid_file="$runtime_root/recorder-health.pid"
recorder_action_lock_file="$runtime_root/recorder-action.lock"
log_file="$state_root/dictation.log"
toggle_lock="$runtime_root/toggle.lock"
pipewire_health_delay_s="${ASSISTANT_PIPEWIRE_HEALTH_DELAY_S:-0.75}"
configured_alsa_device="${ASSISTANT_ALSA_DEVICE:-}"

mkdir -p "$queue_dir" "$runtime_root"
exec 9>"$toggle_lock"
flock -x 9

log() {
	printf '%s - %s\n' "$(date '+%Y-%m-%d %H:%M:%S.%3N')" "$*" >>"$log_file"
}

start_worker() {
	nohup "$script_dir/queue-worker.sh" >>"$log_file" 2>&1 9>&- &
}

wav_bytes() {
	stat -c %s -- "$1" 2>/dev/null || printf '0\n'
}

find_direct_alsa_device() {
	local device
	if [[ -n "$configured_alsa_device" ]]; then
		if [[ "$configured_alsa_device" == *:* ]]; then
			printf '%s\n' "$configured_alsa_device"
		else
			printf 'plughw:%s\n' "$configured_alsa_device"
		fi
		return 0
	fi

	if ! command -v arecord >/dev/null 2>&1; then
		return 1
	fi

	device="$({
		LC_ALL=C arecord -l 2>/dev/null || true
	} | awk '
        tolower($0) ~ /yeti stereo microphone/ {
            match($0, /^card [0-9]+/)
            card = substr($0, RSTART + 5, RLENGTH - 5)
            match($0, /device [0-9]+/)
            subdevice = substr($0, RSTART + 7, RLENGTH - 7)
            if (card != "" && subdevice != "") {
                print "plughw:" card "," subdevice
                exit
            }
        }
    }')"
	if [[ -n "$device" ]]; then
		printf '%s\n' "$device"
		return 0
	fi

	device="$({
		LC_ALL=C arecord -l 2>/dev/null || true
	} | awk '
        match($0, /^card [0-9]+/) {
            card = substr($0, RSTART + 5, RLENGTH - 5)
            match($0, /device [0-9]+/)
            subdevice = substr($0, RSTART + 7, RLENGTH - 7)
            if (card != "" && subdevice != "") {
                print "plughw:" card "," subdevice
                exit
            }
        }
    }')"
	[[ -n "$device" ]] && printf '%s\n' "$device"
}

stop_recorder() {
	local pid="$1"
	local description="$2"
	if ! kill -0 "$pid" 2>/dev/null; then
		return 0
	fi

	log "Stopping $description recorder pid=$pid"
	kill -INT "$pid" 2>/dev/null || true
	for _ in {1..40}; do
		kill -0 "$pid" 2>/dev/null || return 0
		sleep 0.05
	done
	log "Recorder did not stop after 2 seconds; terminating pid=$pid"
	kill -TERM "$pid" 2>/dev/null || true
}

launch_direct_alsa() {
	local audio_file="$1"
	local device
	device="$(find_direct_alsa_device || true)"
	if [[ -z "$device" ]]; then
		log "ERROR: no direct ALSA capture device found"
		return 1
	fi

	arecord -D "$device" -f S16_LE -r 16000 -c 1 "$audio_file" >>"$log_file" 2>&1 9>&- &
	started_recorder_pid=$!
	started_recorder_backend="alsa:$device"
}

fallback_to_direct_alsa_locked() {
	local audio_file="$1"
	local pipewire_pid="$2"
	local current_backend

	[[ -f "$current_file" ]] || return 1
	[[ "$(<"$current_file")" == "$audio_file" ]] || return 1
	current_backend="$(<"$recorder_backend_file")"
	[[ "$current_backend" == "pipewire" ]] || return 1

	stop_recorder "$pipewire_pid" "PipeWire"
	if ! launch_direct_alsa "$audio_file"; then
		log "ERROR: PipeWire capture failed and direct ALSA fallback could not start"
		return 1
	fi

	printf '%s\n' "$started_recorder_pid" >"$pid_file"
	printf '%s\n' "$started_recorder_backend" >"$recorder_backend_file"
	log "Using direct ALSA fallback pid=$started_recorder_pid device=${started_recorder_backend#alsa:} file=$audio_file"
}

fallback_to_direct_alsa() {
	local status
	exec 8>"$recorder_action_lock_file"
	flock -x 8
	fallback_to_direct_alsa_locked "$@"
	status=$?
	exec 8>&-
	return "$status"
}

monitor_pipewire_startup() {
	local audio_file="$1"
	local pipewire_pid="$2"
	local bytes current_backend

	sleep "$pipewire_health_delay_s"
	[[ -f "$current_file" ]] || return 0
	[[ "$(<"$current_file")" == "$audio_file" ]] || return 0
	current_backend="$(<"$recorder_backend_file")"
	[[ "$current_backend" == "pipewire" ]] || return 0

	bytes="$(wav_bytes "$audio_file")"
	if kill -0 "$pipewire_pid" 2>/dev/null && ((bytes > 44)); then
		log "PipeWire capture health check passed bytes=$bytes file=$audio_file"
		return 0
	fi

	log "WARNING: PipeWire capture health check failed pid=$pipewire_pid bytes=$bytes; trying direct ALSA"
	fallback_to_direct_alsa "$audio_file" "$pipewire_pid" || true
}

write_recording_metadata() {
	local audio_file="$1"
	local recording_started_ms="$2"
	local stop_requested_ms="$3"
	local recording_ready_ms="$4"
	local metadata_file metadata_tmp recording_duration_ms stop_finalize_ms

	metadata_file="${audio_file%.wav}.json"
	metadata_tmp="${metadata_file}.tmp.$$"
	recording_duration_ms=-1
	stop_finalize_ms=-1
	if ((recording_started_ms >= 0 && stop_requested_ms >= 0)); then
		recording_duration_ms=$((stop_requested_ms - recording_started_ms))
	fi
	if ((stop_requested_ms >= 0)); then
		stop_finalize_ms=$((recording_ready_ms - stop_requested_ms))
	fi

	if ! {
		printf '{\n'
		printf '  "recording_started_at_ms": %s,\n' "$recording_started_ms"
		printf '  "stop_requested_at_ms": %s,\n' "$stop_requested_ms"
		printf '  "recording_ready_at_ms": %s,\n' "$recording_ready_ms"
		printf '  "recording_duration_ms": %s,\n' "$recording_duration_ms"
		printf '  "stop_finalize_ms": %s\n' "$stop_finalize_ms"
		printf '}\n'
	} >"$metadata_tmp" || ! mv -f -- "$metadata_tmp" "$metadata_file"; then
		log "WARNING: could not persist recording metadata file=$metadata_file"
		/usr/bin/rm -f -- "$metadata_tmp"
	fi
}

stop_recording() {
	local pid audio_file recorder_backend recording_started_ms stop_requested_ms recording_ready_ms audio_bytes
	exec 8>"$recorder_action_lock_file"
	flock -x 8
	pid="$(<"$pid_file")"
	audio_file="$(<"$current_file")"
	recorder_backend="unknown"
	if [[ -f "$recorder_backend_file" ]]; then
		recorder_backend="$(<"$recorder_backend_file")"
	fi
	stop_requested_ms="$(date '+%s%3N')"
	recording_started_ms=-1
	if [[ -f "$recording_started_file" ]]; then
		recording_started_ms="$(<"$recording_started_file")"
	fi
	if [[ -f "$recorder_health_pid_file" ]]; then
		kill "$(<"$recorder_health_pid_file")" 2>/dev/null || true
	fi
	/usr/bin/rm -f "$pid_file" "$current_file" "$recording_started_file" \
		"$recorder_backend_file" "$recorder_health_pid_file"

	stop_recorder "$pid" "$recorder_backend"

	audio_bytes="$(wav_bytes "$audio_file")"
	if ((audio_bytes > 44)); then
		recording_ready_ms="$(date '+%s%3N')"
		write_recording_metadata \
			"$audio_file" "$recording_started_ms" "$stop_requested_ms" "$recording_ready_ms"
		log "Queued completed recording backend=$recorder_backend file=$audio_file bytes=$audio_bytes"
		start_worker
	else
		log "ERROR: discarding invalid recording backend=$recorder_backend file=$audio_file bytes=$audio_bytes (WAV header only or empty)"
		/usr/bin/rm -f -- "$audio_file" "${audio_file%.wav}.json"
	fi
	exec 8>&-
}

start_recording() {
	local audio_file pid recorder_backend recording_started_ms
	audio_file="$queue_dir/recording-$(date '+%Y%m%d-%H%M%S-%N').wav"
	recording_started_ms="$(date '+%s%3N')"

	if command -v pw-record >/dev/null 2>&1; then
		pw-record --container wav --format s16 --rate 16000 --channels 1 "$audio_file" \
			>>"$log_file" 2>&1 9>&- &
		pid=$!
		recorder_backend="pipewire"
	elif command -v arecord >/dev/null 2>&1; then
		if ! launch_direct_alsa "$audio_file"; then
			log "ERROR: no supported recorder found (pw-record or direct ALSA arecord)"
			exit 1
		fi
		pid="$started_recorder_pid"
		recorder_backend="$started_recorder_backend"
	elif command -v parec >/dev/null 2>&1; then
		parec --file-format=wav --format=s16le --rate=16000 --channels=1 \
			--device=@DEFAULT_SOURCE@ "$audio_file" >>"$log_file" 2>&1 9>&- &
		pid=$!
		recorder_backend="parec"
	else
		log "ERROR: no supported recorder found (pw-record, arecord, or parec)"
		exit 1
	fi
	if [[ "$recorder_backend" == "pipewire" ]]; then
		printf '%s\n' "$pid" >"$recorder_health_pid_file"
	fi
	printf '%s\n' "$pid" >"$pid_file"
	printf '%s\n' "$audio_file" >"$current_file"
	printf '%s\n' "$recording_started_ms" >"$recording_started_file"
	printf '%s\n' "$recorder_backend" >"$recorder_backend_file"
	log "Started recording backend=$recorder_backend pid=$pid file=$audio_file"
	if [[ "$recorder_backend" == "pipewire" ]]; then
		monitor_pipewire_startup "$audio_file" "$pid" >/dev/null 2>&1 9>&- &
		printf '%s\n' "$!" >"$recorder_health_pid_file"
	fi
	start_worker
}

if [[ -f "$pid_file" && -f "$current_file" ]]; then
	stop_recording
else
	/usr/bin/rm -f "$pid_file" "$current_file" "$recording_started_file" \
		"$recorder_backend_file" "$recorder_health_pid_file"
	start_recording
fi
