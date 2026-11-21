# E2E contract: UI tags, events, debug commands

The scenarios in `scripts/e2e/S*.sh` depend on the names below. The app must use them exactly.
If one has to change, change the scenario in the same commit.

## Compose test tags (exposed as resource ids)

Debug roots, including the block overlay, set `Modifier.semantics { testTagsAsResourceId = true }`.
The e2e driver matches the plain tag (`block_close`) or `<pkg>:id/<tag>`.

| tag | where | used by |
|---|---|---|
| `block_close` | block screen / overlay: **Close** | S1, S16 (and as cleanup in S6, S9, S10) |
| `block_pause` | block screen: **Pause…** (enabled after 10 s) | reserved (block-screen pause path) |
| `pause_15m` | block screen pause sheet: the 15 min option | reserved |
| `block_allow_browser` | block screen: **Not a browser? Allow** | S15 |
| `schedule_card_<name>` | Home: the card of schedule `<name>` (the schedule's name, verbatim) | reserved |
| `schedule_menu_<name>` | Home: the overflow-menu button on that card | S7 (`Allday`), S11 (`Strictly`) |
| `menu_pause` | Home card menu: **Pause…** | S7 |
| `pause_option_15m` | Home pause dialog: 15 min | S7 |
| `menu_delete` | Home card menu: **Delete** | S11 |
| `locked_by_strict` | the "Locked by Strict Mode" message/dialog shown when a weakening action is refused | S11 |

Seed schedule names avoid spaces, so the tags are plain identifiers.

## e2e driver

```sh
adb shell am instrument -w -e cmd tap|wait|text -e res <tag> [-e text <s>] [-e timeout <ms>] [-e pkg <pkg>] \
    dev.brainrotprevention.e2edriver/.DriverInstrumentation
```

Success is `INSTRUMENTATION_RESULT: result=OK` with `INSTRUMENTATION_CODE: -1`. A failure reports
`result=FAIL`, `error=…` and code 0. `lib.sh` wraps this as `driver_tap`, `driver_wait` and `driver_text`.
The driver sets `FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES`, so our accessibility service stays connected.

## Event log (`BRP`, SPEC §14.3 item 3): field shapes the scenarios assert on

Every line is one compact JSON object, with `ev` first, then (for `mark`) `id`, then the fields, then `t` and `rt`.

| ev | fields the scripts read | example |
|---|---|---|
| `mark` | `id` | `{"ev":"mark","id":"setup","t":…,"rt":…}` |
| `decision` | `target` (package; for web pages the **browser** package), `url` (web only), `result` `ALLOW`/`BLOCK`, `reasons` = list of `{"source":…,"limit":…}`, `until` | `{"ev":"decision","target":"dev.brainrotprevention.fixture.target","result":"BLOCK","reasons":[{"source":"QUICK_BLOCK","limit":null}],"until":"UntilStopped",…}` |
| `enforced` | `how` = `HOME`/`BACK`/`URL_REWRITE`/`VIEW_INTENT`/`OVERLAY`, `target` | |
| `overlay` | `state` = `shown`/`hidden`, `reason` | |
| `usage` | `limitId`, `used` (ms for TIME, count for LAUNCHES), `periodStart` | |
| `alarm` | `kind`, `next` | |
| `alarm_fired` | `kind` (for example `WINDOW_START`, `PAUSE_END`) | |
| `service` | `name` = `a11y`/`listener`, `state` = `connected`/`disconnected` | |
| `notif_posted` | `kind` = `LIMIT_WARNING`/`PAUSE_END`/`BEFORE_START`/`HEALTH` | |
| `notif_hidden` | `pkg` | |
| `strict` | `state` = `INACTIVE`/`ACTIVE`/`STANDBY` | |
| `admin` | `state` = `active`/`removed` (S11 also accepts `strict` with `admin":"removed"`) | |
| `sensor` | `ssid` (string or `null`); location rows | |
| `browsers` | `unsupported` = list of packages | |
| `boot`, `error` | | |

`reasons[].source` is the reason name. For a schedule it is `SCHEDULE` with a `scheduleId` field, or the
string `SCHEDULE(<id>)`. For an unsupported browser it is `UNSUPPORTED_BROWSER`, optionally with `(<id>)`. The
jq helper `has_reason("X")` matches `X`, `X(…)` and `X:…`, and also accepts plain strings in `reasons`.
`has_limit("TIME")` looks at `reasons[].limit` and a top-level `limit`.

Fixture apps log on tag `BRPFIX`: `page` (`url`), `nav`, `submit`, `typed`, `resumed`, `pip` (`mode`), `media`, `posted`.

## Debug-control commands used by the scenarios

`mark` (implemented), `reset`, `import --es b64|path`, `export`, `state`, `clock-set --es at`, `clock-advance --el ms`,
`clock-jump --el ms`, `qb-start`, `strict-start --es method --el ms --ei options`, `sensor-real`,
`snooze-chunk --el ms`. Add each one with `DebugCommands.register(name) { intent -> … }`, in
`app/src/hooks/kotlin/dev/brainrotprevention/debug/BuiltinDebugCommands.kt`.

## G3 instrumentation contract (verify.sh)

From M3, `verify.sh` runs the app's androidTest APK twice with `am instrument -w -r`:

- first with `-e annotation dev.brainrotprevention.testing.NoGrant`, after `ungrant.sh`
- then with `-e notAnnotation dev.brainrotprevention.testing.NoGrant`, after `grant.sh`

Both runs pass `-e filter dev.brainrotprevention.testing.MilestoneFilter -e milestone M<n>`. `MilestoneFilter`
(an `org.junit.runner.manipulation.Filter` with a no-arg constructor) reads `milestone` from
`InstrumentationRegistry.getArguments()`. It keeps tests whose `@Milestone("Mx")` (on the method or the class) is
at or below that milestone. The `NoGrant`, `Milestone` and `MilestoneFilter` classes belong in an androidTest
dependency, for example `testing/fixtures`. Below M3, G3 builds, installs and grants only.
