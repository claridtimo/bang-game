# Bang! Howdy Modernization Plan

Goal: upgrade Bang! Howdy to **Java 21 LTS + Gradle 8**, modernizing dependencies along the
way, while keeping the vendored jME engine fork. Acceptance bar: **the full game runs** —
`gradle deploy` succeeds, the server boots against MySQL, the client renders, and the `test`
user can log in and reach a game. A scoped roadmap for a later jMonkeyEngine 3.8 port is
included as Phase 5 (not executed in this effort).

Decisions locked (2026-06-13): Java 21 · keep jME fork (Linux/Windows only) · full-game-runs
acceptance · parallel background agents in worktrees · `/code-review ultra` as phase gate.

## Ground truth (verified)

- Machine: Ubuntu 24.04, JDK 21.0.11 (only JDK present), Gradle 4.4.1 (cannot run on JDK 21
  — the legacy build is unrunnable here; no baseline build, we migrate forward only).
- MySQL: not installed. Required for acceptance.
- All upgraded Three Rings artifacts are on Maven Central — no source builds of siblings
  needed: narya 1.19, narya-tools 1.19, nenya 1.7.2, nenya-tools 1.7.2, vilya 1.7.1,
  depot 1.8, ooo-util 1.5, samskivert 1.10, ooo-user 1.4.5.
- Reference clones for API diffs: `../narya`, `../nenya`, `../vilya`, `../jmonkeyengine`.
- Engine reality: 240 client files use the vendored jME fork API, 61 use LWJGL 2.9 directly,
  25 use libGDX 1.5.4 (window/input only, LWJGL2 backend). **gdx stays at 1.5.4** — newer gdx
  moves to LWJGL3 and would break the shared GL stack. LWJGL 2.9.2 stays.
- narya 1.15→1.19 is API-stable; pain points are Guice 3→5.1, Java 10+ bytecode, optional
  deserialization hardening (`ObjectInputStream.setAllowedClassPrefixes`), and regenerating
  all `*Marshaller`/`*Dispatcher`/`*Provider` code with the new narya-tools templates.

## Branch & agent workflow

- Integration branch: `modernize` off `master`. One coordinator (main session) owns it.
- Parallel workstreams run as background agents in **isolated git worktrees**, each producing
  a reviewable branch merged into `modernize` by the coordinator. Streams are listed per phase.
- Phase gates: coordinator verifies the phase's exit criteria, then the user runs
  `/code-review ultra` on `modernize` before the next phase starts.
- Agents must not bump versions beyond what this plan pins without flagging it back.

## Phase 0 — Environment (coordinator, sequential, ~hours)

1. Install MySQL (or MariaDB) locally; create `bang` and `ooouser` databases + user.
2. Copy `etc/*.dist` → `etc/test/*` (drop `.dist`); set `db.*` values in
   `etc/test/server.properties`. MySQL 8 note: create the DB user with
   `mysql_native_password` or verify Connector/J 8 handles `caching_sha2_password`.
3. Create `modernize` branch.

Exit: MySQL up with both schemas; configs in place.

## Phase 1 — Build system migration (single agent — touches everything, ~days)

The critical path. One agent, one branch, no parallelism (every module's build file changes).

1. Add Gradle wrapper 8.x. Convert all `build.gradle` files:
   - `compile` → `api`/`implementation`; `provided` → `compileOnly` + test equivalents;
     `testCompile` → `testImplementation`.
   - `task foo <<` → `tasks.register("foo") { doLast { ... } }`.
   - `sourceSets.main.output.classesDir` → `classesDirs` (copyConfig tasks in
     `server` and `client/desktop`).
   - `assets` module: the ant-taskdef-driven resource pipeline (`genConfigs`, `genBundles`,
     `compileModels`, `updateLists`) should keep working via Gradle's AntBuilder — verify
     each task, fix wiring/up-to-date declarations.
2. Java toolchain 21 (`java.toolchain.languageVersion = 21`), UTF-8 encoding, keep
   `-Xlint` set roughly as-is.
3. Dependency bumps (pinned): narya 1.19, narya-tools 1.19, nenya 1.7.2, nenya-tools 1.7.2,
   vilya 1.7.1, depot 1.8, ooo-util 1.5, samskivert 1.10, guice 5.1.0 (transitive — align if
   declared), junit 4.13.2, mysql-connector-j 8.x (keep legacy
   `com.mysql.jdbc.Driver` alias in configs or update to `com.mysql.cj.jdbc.Driver`),
   commons-* minor bumps only if compilation forces them. ehcache 1.6, getdown 1.4,
   lwjgl 2.9.2, gdx 1.5.4 **unchanged**.
4. Get `gradle compileJava` green across all modules on JDK 21. Expected work:
   - Regenerate generated RPC classes: `gradle client:shared:genService` with
     narya-tools 1.19 (large mechanical diff — keep as its own commit).
   - Guice 3→5.1 fallout in `server` bootstrap (`BangServer` / `PresentsServer` wiring).
   - depot 1.7→1.8 API changes in `server/persist` and `*/server/persist` records
     (use `../narya` + Central depot sources as reference).
   - Misc JDK 21 compile errors (removed/encapsulated APIs, varargs/generics strictness).

Exit: `./gradlew build -x test` green on JDK 21; `gradle deploy` produces
`build/client`, `build/server`, `build/assets`.

## Phase 2 — Runtime fixing (parallel agents in worktrees)

Streams (each its own worktree branch off `modernize`):

- **Stream A — client runtime (jME fork + LWJGL on JDK 21).** Get `./bin/bangclient` to
  render. Known risk areas: LWJGL 2.9 `LinuxDisplay`/xrandr parsing on modern X servers
  (force XWayland; known output-format crashes), JDK strong encapsulation (`--add-opens`
  flags in `bin/bangclient` / `bin/bangjava` as needed), AWT interactions, `sun.misc.*`
  usage inside the fork and BUI, OpenGL compatibility-profile context creation.
- **Stream B — server runtime.** Get `./bin/bangserver` to boot and build its schema:
  depot 1.8 against MySQL 8 (schema creation, migrations), Connector/J 8 behavior
  (timezone, auth plugin), ehcache 1.6 on JDK 21 (bump to 2.x only if broken), Guice
  runtime errors, `gradle server:createTestUser`. Enable narya's deserialization
  whitelist at startup (`setAllowedClassPrefixes`: `com.threerings.`, `com.samskivert.`,
  `java.lang.`, `java.util.` — verify the exact set narya requires) and confirm Bang's
  large streamed objects (boards) fit the new 65k container cap, raising the configured
  limits if not.
- **Stream C — asset pipeline + launch scripts.** Verify `assets:deploy` output matches
  what client/server expect (per-town jars, compiled configs, avatar bundles, models);
  modernize `bin/*` scripts (classpath construction, JVM flags, remove dead branches);
  verify `./bin/bangeditor`.

Exit: client renders the town view; server reaches "Bang server initialized"; assets load.

## Phase 3 — Integration & acceptance (coordinator)

1. Merge streams into `modernize`; full `gradle deploy` from clean.
2. End-to-end: server up → `createTestUser` → client login as `test`/`yeehaw` → reach
   saloon → start a game vs. tinhorns (AI) or `-tutorial` → play several turns.
3. `gradle test` green (3 existing unit test classes — fix, don't delete).
4. Sweep logs for swallowed errors; document required JVM flags and MySQL setup in README.
5. Update CLAUDE.md (build commands change: `./gradlew`, no legacy-Gradle warning).

Exit = acceptance bar. User runs `/code-review ultra` on the final diff.

## Phase 4 — Cleanup (optional, parallel-friendly)

- Remove Maven `pom.xml` files (or regenerate to match — pick one build system).
- Delete dead infra: `old-build.xml`, unused `bin/` scripts, `webapps`/`pages` rot — verify
  before deleting, propose list first.
- Address high-value `-Xlint` warnings; leave cosmetic ones.

## Phase 5 — jME 3.8 port roadmap (NOT executed; planning artifact)

Scoped outline for a future campaign, in dependency order:

1. **Inventory** (1–2 agent-days): catalog the fork API surface actually used by the 240
   client files + BUI; map each class to its jME 3.8 equivalent (`com.jme.scene.Node` →
   `com.jme3.scene.Node`, controllers → `Control`s, fixed-function material states →
   `Material`/shader params). Output: migration table + risk list.
2. **Model pipeline**: replace the XML→`.jme` binary compiler (`CompileModelTask`) with a
   converter to jME3 `.j3o` (likely via a custom loader reusing the existing XML parse);
   this unblocks everything visual.
3. **BUI**: port BUI's render layer to jME3 (quad/texture/text rendering) — BUI's API stays,
   its internals change. Alternative: migrate UI to Lemur (jME3's de-facto UI lib) — bigger
   diff, better support.
4. **Core client**: `app` module (`com.threerings.jme.*` framework: JmeApp loop, camera
   handlers, sprites/effects) onto jME3's application model.
5. **Game board renderer**: terrain splatting, water, skies, particle effects
   (`rsrc/effects/*/particles.jme`) — the highest-effort, highest-fidelity-risk area.
6. **Switch windowing to LWJGL3** (comes free with jME3) and then optionally bump libGDX
   or remove it entirely (it only does window/input, which jME3 covers).
7. Per-town visual regression pass against screenshots captured from the Phase 3 build.

Estimate: 2–3 months of agent-driven work with human visual review; gains modern
macOS/ARM support and a maintained engine.

## Risk register

| Risk | Mitigation |
|---|---|
| LWJGL 2.9 broken on Wayland/modern X | Run under XWayland; known xrandr crash has documented workarounds; worst case patch the fork's display init (we own it) |
| MySQL 8 auth/charset vs Connector/J | Pin `mysql_native_password` user; set explicit `serverTimezone`/charset in JDBC URL |
| depot 1.8 schema behavior differs on fresh MySQL 8 | Fresh-create both schemas (no legacy data to migrate) |
| Generated-code regen produces huge diff hiding real changes | Isolate regen in a dedicated commit per module |
| Compiled binary configs (`.dat`, bundles) incompatible with nenya 1.7.2 readers | Assets are rebuilt from source XML by our own pipeline each deploy — regen, don't reuse |
| getdown 1.4 on JDK 21 | Only needed for distribution; out of scope for local acceptance |
