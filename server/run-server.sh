#!/bin/zsh
cd "$(dirname "$0")" || exit 1
[ -f ./.env ] && { set -a; source ./.env; set +a; }
exec ./.venv/bin/python szept_server.py
