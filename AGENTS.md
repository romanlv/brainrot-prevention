# Agent loop

Copied from SPEC.md §15 (`../SPEC.md`). The spec is the source of truth. Event, tag and debug-command contracts for the scenarios are in `scripts/e2e/TAGS.md`.

1. **Start every session with `scripts/doctor.sh`.** If it isn't ok, fix the environment first. Never change app code because of an `infra` failure.
2. **Work milestone by milestone (§16)** and record the current one in `build/verify/milestone`.
3. **Write or extend the `core:engine` tests first**, from the rules in §5–9. Implement until G1 is green.
4. **Wire the Android side** and add or extend the scenario for the item.
5. **Run `scripts/verify.sh`**, and when it's green, `scripts/verify.sh --device`.
6. **Trust `report.json` only** if `complete==true` and the `run_id` is from the run you just started. On failure, read the report, then the events, logcat, UI dump and screenshot it lists. Fix, re-run only the failing gate or scenario (`--only`), and run the full suite once at the end.
7. **Handle infra failures:** if a failure has `kind=infra` and also failed on its automatic retry, run `scripts/emu.sh reset` once. If it still fails, stop and report. Don't change code.
8. **A scenario is done after 3 clean passes in a row** (`--repeat 3`). Each repeat starts with `ctl reset`, a fresh seed and a new log collector. S13 is repeated only at the end of its milestone.
9. **Never weaken an assertion,** skip a test or delete a scenario to get green. If the spec is wrong or impossible on this Android version, write the evidence in `docs/DEVIATIONS.md` and pick the closest behaviour.
10. **If the same assertion fails 5 iterations in a row** with different fixes, stop. Write the evidence under "Open" in DEVIATIONS.md and move to the next item.
11. **Commit** after each green milestone, naming the scenarios that pass.
12. **Never** set `ALLOW_PHYSICAL`, run `adb kill-server`, use `adb -d`, call `/opt/homebrew/bin/adb` directly, or run `./gradlew install*` or `./gradlew connected*`.
13. **Before ending a session:** run `scripts/emu.sh down` and `./gradlew --stop`.

