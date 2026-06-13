# Running Bang! Howdy for testing (jME3 / LWJGL3)

This is the standard run sheet for launching the migrated client/server to test or screenshot a
change. **Use `bin/devtest`** — it codifies the setup so you don't re-derive (and mis-handle) it.
Most automated run failures have come from skipping one of these steps; the script does them in
order and always tears down.

## `bin/devtest` — the one command

```sh
bin/devtest [--board "<name>"] [--node frontier_town|indian_post] \
            [--shot <path>] [--hold <secs>] [--no-deploy] [--sound] [--no-client]
```

What it does, in order: **(1)** kills stale Bang processes and frees port `47624`; **(2)** verifies
MySQL is up and `bang`/`yeehaw` connects; **(3)** `./gradlew deploy` (skip with `--no-deploy`),
aborting on failure; **(4)** starts the server and **waits until it logs `Server listening`** before
going on; **(5)** launches the client with the standard flags (`-test`/`-autoplay`, creds, muted —
see below); **(6)** with `--shot`, holds `--hold` seconds then screenshots `$DISPLAY` to the path;
**(7)** always tears everything down on exit (even Ctrl-C), leaving `47624` free.

Examples:
```sh
bin/devtest --shot /tmp/town.png --hold 35                          # smoke test → capture → teardown
bin/devtest --board "Thunderbird Rock" --node indian_post --shot /tmp/water.png
bin/devtest --no-deploy                                             # quick interactive run on current build
bin/devtest --no-client                                            # server only
```

- **Agents: always use `--shot` mode.** It auto-tears-down and exits, so it is deterministic and
  never hangs (plain mode blocks on the client until Ctrl-C). Logs: `/tmp/devtest-server.log`,
  `/tmp/devtest-client.log`.
- `DISPLAY` defaults to `:1` (override via the env var).

## Gotchas the script handles for you (and a couple it can't)

- **Audio is muted by default on this branch** (dev convenience — `BangPrefs.SILENT`). Pass
  `--sound` (→ `-Dsound=true`) to hear it. *(Phase-6 reminder: revert to audible-by-default before
  ship.)*
- **MySQL must be running** (`bang`/`ooouser` DBs, `bang`/`yeehaw` user — see README). The script
  verifies and aborts with instructions if not; it does **not** `sudo systemctl start mysql` for you
  (that's interactive).
- **`indian_post` node** only serves its boards if `etc/test/server.properties` has
  `indian_post.town_id = indian_post` (otherwise `ServerConfig.townId` falls back to
  `frontier_town` and the indian_post boards are skipped → `getBoard()` NPE). `etc/test/` is local
  (gitignored); set this if you use `--node indian_post`.
- **Don't launch the client before the server is listening** — the script polls the log so you
  can't; doing it by hand races into `BindException`/"connection refused".

## Deterministic alternatives (no live client needed)

For per-change *visual* verification without the flaky live path, prefer the offscreen
render-to-PNG harnesses (Phase-7 #1 — they render through the real load/resolve path with no
window, on an isolated LWJGL3 classpath):

```sh
./gradlew :tools:j3o-converter:renderToPng  -PmodelType=props/frontier_town/buildings/saloon -Pout=/tmp/saloon.png
./gradlew :tools:j3o-converter:renderWaterToPng -Pout=/tmp/water.png
```

## Known live-render defects (see docs/jme3-cutover-plan.md "Known live-render defects")

- **WASD camera panning doesn't work** — Phase-3 input gap (`GodViewHandler.registerWith(InputManager)`
  not wired). You can't pan to compose shots yet; pick a board whose default camera frames the subject.
- Sideways claim-counter numbers (`BillboardControl` Y-up vs Z-up board) and the wrong horse model
  are queued there too.
