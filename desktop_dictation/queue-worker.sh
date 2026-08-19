#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
runtime_root="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}/assistant-desktop-dictation"
state_root="${XDG_STATE_HOME:-$HOME/.local/state}/assistant-desktop-dictation"
queue_dir="$state_root/queue"
worker_lock="$runtime_root/worker.lock"
retry_delay_s="${ASSISTANT_QUEUE_RETRY_INITIAL_S:-2}"
max_retry_delay_s="${ASSISTANT_QUEUE_RETRY_MAX_S:-30}"

mkdir -p "$queue_dir" "$runtime_root"
exec 9>"$worker_lock"
flock -n 9 || exit 0

shopt -s nullglob
while true; do
	recordings=("$queue_dir"/recording-*.wav)
	((${#recordings[@]} > 0)) || exit 0

	recording="${recordings[0]}"
	metadata="${recording%.wav}.json"
	if [[ -f "$runtime_root/current-audio-path" ]] &&
		[[ "$(<"$runtime_root/current-audio-path")" == "$recording" ]]; then
		exit 0
	fi

	if UV_CACHE_DIR="${UV_CACHE_DIR:-/data/.cache/uv}" \
		uv run --project "$script_dir/.." python "$script_dir/transcribe-and-type.py" "$recording"; then
		/usr/bin/rm -f -- "$recording" "$metadata"
		retry_delay_s="${ASSISTANT_QUEUE_RETRY_INITIAL_S:-2}"
	else
		printf '%s - Queue paused after failed transcription; preserved %s; retrying in %ss\n' \
			"$(date '+%Y-%m-%d %H:%M:%S.%3N')" "$recording" "$retry_delay_s" >&2
		sleep "$retry_delay_s"
		retry_delay_s="$((retry_delay_s * 2))"
		if ((retry_delay_s > max_retry_delay_s)); then
			retry_delay_s="$max_retry_delay_s"
		fi
	fi
done
