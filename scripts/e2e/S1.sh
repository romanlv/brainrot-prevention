#!/usr/bin/env bash
# S1 Quick Block (SPEC §14.8)
# Seed Quick Block {T}; qb-start; launch T -> BLOCK QUICK_BLOCK + HOME + overlay; Close -> launcher; relaunch -> BLOCK; PiP -> blocked.
set -euo pipefail
source "$(dirname "$0")/lib.sh"
scenario_begin
step qb-start
ctl qb-start
quick_block_assertions
pass
