#!/usr/bin/env bash
set -euo pipefail

runtime="${XDG_RUNTIME_DIR:-/run/user/$(id -u)}"
export YDOTOOL_SOCKET="${YDOTOOL_SOCKET:-$runtime/.ydotool_socket}"
modifier_state_file="${WAYLAND_MODIFIER_STATE_FILE:-$runtime/wayland_modifier_state.env}"
lock="$runtime/assistant-ydotool-type.lock"

exec 9>"$lock"
flock -x 9

ydotool_ready() {
    ydotool key --key-delay 0 29:0 >/dev/null 2>&1
}

ensure_ydotoold() {
    ydotool_ready && return 0
    systemctl --user start ydotool.service >/dev/null 2>&1 ||
        systemctl --user start ydotoold.service >/dev/null 2>&1 ||
        true
    for _ in {1..5}; do
        ydotool_ready && return 0
        sleep 0.05
    done
    printf 'wayland-type-helper: ydotoold is not reachable at %s\n' "$YDOTOOL_SOCKET" >&2
    return 4
}

modifier_held() {
    [[ -f "$modifier_state_file" ]] || return 1
    # shellcheck disable=SC1090
    source "$modifier_state_file"
    [[ "${META:-0}" == 1 || "${SHIFT:-0}" == 1 || "${CTRL:-0}" == 1 || "${ALT:-0}" == 1 ]]
}

ensure_ydotoold
if modifier_held; then
    printf 'wayland-type-helper: refusing to type because a modifier key is currently held\n' >&2
    exit 5
fi

# Release stale key state only after proving the user is not holding a modifier.
ydotool key --key-delay 0 110:0 42:0 54:0 29:0 97:0 56:0 100:0 125:0 126:0 47:0 \
    >/dev/null 2>&1 || true
sleep 0.05
ydotool type --key-delay 0 --key-hold 0 --file=-
