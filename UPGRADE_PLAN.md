# Bang! Howdy Modernization Plan

Goal: upgrade Bang! Howdy to **Java 21 LTS + Gradle 8**, modernizing dependencies along the
way, while keeping the vendored jME engine fork. Acceptance bar: **the full game runs** —
`./gradlew deploy` succeeds, the server boots against MySQL, the client renders, and the `test`
user can log in and reach a game. A scoped roadmap for a later jMonkeyEngine port (latest stable; 3.9.0 as of June 2026) is
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
   *Phase 2 amendment:* ooo-user 1.4.5 → **1.5.1** (1.4.5 is binary-incompatible with
   depot 1.8 — `NoSuchMethodError: ColumnExp.in(Iterable)`; 1.5.1 is the first release
   built against depot 1.8). Flagged by Stream B, accepted by coordinator.
4. Get `./gradlew compileJava` green across all modules on JDK 21. Expected work:
   - Regenerate generated RPC classes: `./gradlew client:shared:genService` with
     narya-tools 1.19 (large mechanical diff — keep as its own commit).
   - Guice 3→5.1 fallout in `server` bootstrap (`BangServer` / `PresentsServer` wiring).
   - depot 1.7→1.8 API changes in `server/persist` and `*/server/persist` records
     (use `../narya` + Central depot sources as reference).
   - Misc JDK 21 compile errors (removed/encapsulated APIs, varargs/generics strictness).

Exit: `./gradlew build -x test` green on JDK 21; `./gradlew deploy` produces
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
  runtime errors, `./gradlew server:createTestUser`. Enable narya's deserialization
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

1. Merge streams into `modernize`; full `./gradlew deploy` from clean.
2. End-to-end: server up → `createTestUser` → client login as `test`/`yeehaw` → reach
   saloon → start a game vs. tinhorns (AI) or `-tutorial` → play several turns.
3. `./gradlew test` green (3 existing unit test classes — fix, don't delete).
4. Sweep logs for swallowed errors; document required JVM flags and MySQL setup in README.
5. Update CLAUDE.md (build commands change: `./gradlew`, no legacy-Gradle warning).

Exit = acceptance bar. User runs `/code-review ultra` on the final diff.

## Phase 4 — Cleanup (optional, parallel-friendly)

- Remove Maven `pom.xml` files (or regenerate to match — pick one build system).
- Delete dead infra: `old-build.xml`, unused `bin/` scripts, `webapps`/`pages` rot — verify
  before deleting, propose list first.
- Address high-value `-Xlint` warnings; leave cosmetic ones.

## Phase 5 — jME 3.x port roadmap (target latest stable; 3.9.0 as of June 2026)

Step 1 (inventory) and prototypes for steps 2 (model pipeline) and 3 (BUI) are DONE on the
`JMEupgrade` branch — see `docs/jme3-migration-map.md`, `docs/jme3-model-pipeline.md`,
`docs/jme3-bui-port.md` and the `tools/j3o-converter` module. The map supersedes the
estimates below; corrections from it are folded in here. Verified scope: **239 fork-importing
files, 93 distinct fork classes → 18 DIRECT / 41 ADAPT / 25 REBUILD / 10 DROP**.

Ordering (bottom-up; steps 1–4 are engine-agnostic and land while the legacy LWJGL2 host
still boots — the LWJGL2→LWJGL3 switch is a single atomic cutover at step 5, NOT a free late
step):

1. **Inventory & API map** — DONE (`docs/jme3-migration-map.md`).
2. **Model pipeline** — prototype DONE (310/310 mesh-stat parity via `tools/j3o-converter`).
   Note `CompileModelTask` is **project-owned** (in `app/`, emits `model.dat` — not nenya-tools,
   not `.jme`), so we own the rewrite. Recommendation from the prototype: a runtime jME3
   `AssetLoader<Model>` reading `model.dat` directly rather than a build-time `.j3o` bake
   (ModelCache already re-resolves colorization/variants/detail at runtime). ~76% of models are
   static (full fidelity now); animated/skinned 21% map onto `com.jme3.anim`
   (`AnimClip`/`TransformTrack`, `Armature`+`SkinningControl`). ~2–3 agent-weeks for full coverage.
3. **BUI render layer** — seam + compile-isolated jME3 backend DONE. Decision: **reimplement
   BUI internals on jME3** (not Lemur — BUI's 42 `renderComponent(Renderer)` paint-hook overrides
   can't be expressed in retained-mode Lemur). Text ports for free: `AWTTextFactory` already
   renders to a `BufferedImage`, whose BGR/ABGR bytes map onto jME3 `BGR8`/`ABGR8` with no
   reshuffle. Remaining: type-token swap across bui+app+client/shared, `Jme3RootNode`+input,
   `BGeomView` on ViewPorts, cursor/window glue (with step 5).
4. **Core client**: `app` module (`com.threerings.jme.*`: JmeApp loop, camera handlers,
   sprite/path system, ShaderCache) onto jME3's application model.
5. **Atomic LWJGL2→LWJGL3 cutover** at the app-host boundary (`BangDesktop`/`BangApp`/
   `EditorDesktop`): the two engines cannot share a window/GL context, so gdx+LWJGL2 flips to
   jME3+LWJGL3 in one move, landed together with step 4. **This drags sound with it** — retiring
   gdx removes the OpenAL device owner, so the vendored `com.threerings.openal` package must move
   to LWJGL3 (or jME3 audio) in the same step. Then the board renderer: terrain splatting, water,
   skies, and particle effects (60 `particles.jme` files, structural rewrite).
6. **Asset conversion tool** (NEW — absent from the original plan): the 168 `.board` + 196
   `.game` files are jME-fork `BinaryExporter` Savables with **no XML source**; they need a
   one-time fork→jME3 conversion tool, not a recompile (310 `model.dat` DO regenerate from XML).
7. Per-town visual regression pass against screenshots captured from the Phase 3 build.

Revised estimate (from the verified map): **~70–110 agent-days (3.5–5.5 months)** for the
REBUILD work before integration/regression — the earlier "2–3 months" is optimistic but
reachable with heavy parallelism. Gains modern macOS/ARM support and a maintained engine.

## Risk register

| Risk | Mitigation |
|---|---|
| LWJGL 2.9 broken on Wayland/modern X | Run under XWayland; known xrandr crash has documented workarounds; worst case patch the fork's display init (we own it) |
| MySQL 8 auth/charset vs Connector/J | Pin `mysql_native_password` user; set explicit `serverTimezone`/charset in JDBC URL |
| depot 1.8 schema behavior differs on fresh MySQL 8 | Fresh-create both schemas (no legacy data to migrate) |
| Generated-code regen produces huge diff hiding real changes | Isolate regen in a dedicated commit per module |
| Compiled binary configs (`.dat`, bundles) incompatible with nenya 1.7.2 readers | Assets are rebuilt from source XML by our own pipeline each deploy — regen, don't reuse |
| getdown 1.4 on JDK 21 | Only needed for distribution; out of scope for local acceptance |
