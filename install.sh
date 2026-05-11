#!/usr/bin/env bash
set -euo pipefail

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing required command: $1" >&2; exit 1; }; }

need python3
need oc

require_context() {
  local expected="${1:-}"
  [[ -n "${expected}" ]] || return 0
  local current
  current="$(oc config current-context 2>/dev/null || true)"
  if [[ "${current}" != "${expected}" ]]; then
    echo "ERROR: oc context must be '${expected}' (current: '${current:-<none>}')." >&2
    echo "Run: oc config use-context ${expected}" >&2
    exit 1
  fi
}

require_context "${RHCL_REQUIRED_CONTEXT:-rhcl}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="${VENV_DIR:-${ROOT_DIR}/.venv}"

if [[ ! -d "${VENV_DIR}" ]]; then
  python3 -m venv "${VENV_DIR}"
fi

# shellcheck disable=SC1090
source "${VENV_DIR}/bin/activate"

python3 -m pip install --upgrade pip >/dev/null
python3 -m pip install -r "${ROOT_DIR}/ansible/requirements.txt" >/dev/null

export ANSIBLE_ROLES_PATH="${ROOT_DIR}/ansible/roles:${ANSIBLE_ROLES_PATH:-}"
exec ansible-playbook -i localhost, -c local "${ROOT_DIR}/ansible/playbooks/install.yaml" "$@"

