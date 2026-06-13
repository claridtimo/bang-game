# Engine & asset-pipeline notes

Hard-won knowledge about how Bang! Howdy's rendering, map, and asset systems actually work,
collected during the 2026 Java 21/Gradle 8 modernization. Things here are non-obvious from
reading any single file; each section notes the code that proves it.

## The rendering stack is three layers sharing one GL context

```
libGDX 1.5.4 (LwjglApplication)   — window, input, GL context creation, main loop
  └─ jME fork (jme/, app/)        — scenegraph, render states, models, camera
       └─ BUI (bui/)              — in-game UI, rendered inside the jME scene
```

libGDX owns the window and the **LWJGL 2** GL context; the jME fork renders into that same
context every frame (driven from `BangApp` — the gdx `ApplicationListener`). This coupling is
why gdx is pinned at 1.5.4: newer gdx releases moved to an LWJGL 3 backend, which would pull
the context out from under the jME fork. Upgrading either gdx or LWJGL means porting the whole
stack at once (see UPGRADE_PLAN.md Phase 5).

Sound is the same story: gdx opens the OpenAL device, and the `com.threerings.openal` package
**vendored into client/shared** (taken from nenya pre-LWJGL3; nenya 1.7.2's own copy is
LWJGL3-only and binary-incompatible) shares that device. The vendored copy shadows nenya's via
project-jars-first classpath ordering in `bin/bangjava` — if that ordering breaks, you get
`NoSuchMethodError: ALC10.alcOpenDevice(ByteBuffer)` at `SoundManager` init. Shutdown ordering
matters too: killing the JVM with the AL device open intermittently SIGSEGVs in openal-soft's
mixer thread, which is why `BangDesktop` routes SIGTERM/SIGINT through
`LwjglApplication.exit()` for ordered teardown.

## Threading rules

- In the game client, the gdx render thread is where GL lives; Narya's client run queue is
  pumped from it (`LwjglApplication.executeRunnables`).
- In the **editor**, everything (gdx loop, Narya `RunQueue.AWT`, GL) runs on the AWT EDT, so
  the GL context must be *created* on the EDT: gdx's `LwjglCanvas` creates the context
  synchronously from `addNotify` during `frame.pack()`, on whatever thread calls it
  (`EditorDesktop` wraps construction in `EventQueue.invokeLater` for this reason — the editor
  was broken from the 2017 gdx conversion until 2026 because it didn't).
- `LwjglCanvas` creates the GL display **before AWT layout**, while the canvas is still 1×1.
  Anything sized at init time (renderer, camera frustum) is sized 1×1 and must be fixed in
  `JmeApp.resize()` (`renderer.reinit` + frustum refresh). Symptoms of getting this wrong:
  uniform clear-color viewport, picking coordinates in the tens of thousands.
- Scene-graph node constructors may query GL capabilities (e.g.
  `WaterNode.<init>` calls `GLContext.getCapabilities()` at WaterNode.java:63) — they can only
  be constructed on the GL thread.

## The model pipeline

Source of truth: `assets/rsrc/**/model.properties` (+ sibling XML/textures). The build
compiles each into a binary `model.dat` via nenya-tools' `CompileModelTask`
(assets/build.gradle `compileModels`), staged at:

```
assets/build/staging/rsrc/<same path as source>/model.dat     ← note the rsrc/ prefix
```

That `rsrc/` prefix has caused two real bugs — anything verifying staged outputs must include
it. 310 models currently compile (the `boom_town` town is excluded from packaging but its
models still compile). At runtime models load through `ModelCache` →
`Model.readFromFile` (app/.../jme/model/Model.java).

Pipeline gotchas, all verified the hard way:
- `CompileModelTask` **swallows per-file errors** and one observed run produced zero outputs
  while reporting BUILD SUCCESSFUL; assets/build.gradle now has a per-source assertion that
  fails the build on any missing `model.dat`.
- The pipeline **self-mutates the source tree by design**: `updatepropheight` rewrites
  `prop.properties` heights, and `update_lists` regenerates `rsrc/terrain/codes.txt` and the
  props/units/bonuses/sounds `.txt` indexes that runtime code reads
  (e.g. `PropConfig.java:105`, `UnitConfig.java:422`).
- The staging dir doubles as the dev-runtime resource root: `bin/` scripts point
  `-Dresource_dir` at `assets/build/staging/rsrc`. Per-town jars (general / frontier_town /
  indian_post) are only consumed by the getdown distribution path.
- Avatar art ships as bundles: `rsrc/avatars/metadata.jar` + per-town/sex `components.jar`,
  consumed via nenya's `ResourceManager.initBundles` → `BundledComponentRepository` →
  `AvatarLogic` (wired by `etc/.../rsrc/config/resource/manager.properties`).

## Boards (maps)

A "board" is a serialized `BoardData` written with Narya's streaming system — `.board` files
under `data/boards/<town>/<players>/` relative to `server_root` (165 shipped) plus per-town
menu backdrops (`rsrc/menu/<town>/town.board`). The server loads all boards at startup
("Loading boards...").

What's actually inside on the wire: `com.threerings.bang.*` piece/board classes,
`com.threerings.util.StreamableHashMap`, `java.lang.String`, and primitive arrays. The
largest single array is the town-board heightfield: **201×201 = 40,401 elements** (a `byte[]`,
~40 KB payload) — comfortably under narya 1.19's default 65,536 container cap, but worth knowing if
boards ever get bigger (the cap is configurable via `com.threerings.io.maxContainerSize`).
The terrain is splat-textured heightfield; water is a reflective `WaterNode` (GL-thread-only,
see above); `rsrc/terrain/codes.txt` maps terrain type codes.

The board editor (`./bin/bangeditor`) is a Swing app embedding the GL canvas; it runs its own
in-process server and does not need MySQL.

## Narya/wire-protocol behaviors that bit us

- **Sessions survive disconnects.** Reconnecting as the same user *resumes* the server-side
  session ("Session resumed" in the log), keeping the token ring minted at original auth.
  Consequence: permission grants (e.g. the admin token in `ooouser.users.tokens`) take effect
  only on a *fresh* session — restart the server or wait for session expiry.
- **Streaming any enum touches depot.** narya 1.19's `Streamer.create` hard-references
  `com.samskivert.depot.util.ByteEnum` under its default enum policy, so depot must be on the
  **client** runtime classpath even though depot is "server" tech (hence the `runtimeOnly`
  depot dep in client/desktop/build.gradle).
- The server enables narya's deserialization whitelist at startup
  (`BangServer.main` → `ObjectInputStream.setAllowedClassPrefixes`); if a legitimate new
  streamed type appears outside `com.threerings./com.samskivert./java.lang./java.util.`,
  logon will mysteriously fail — check the whitelist first.
- Client logon dev path: `-Dusername`/`-Dpassword` auto-logon lives in
  `LogonView.progress()`; `-test`/`-autoplay` go through `PlayerService.playComputer`, where
  only the autoplay variant is **admin-gated server-side** (`PlayerManager.java:559`).

## Client boot flow (dev)

`bin/bangclient` → `BangDesktop` (gdx `LwjglApplication` + signal handlers) →
`BangApp.create()` (gdx lifecycle; jME display init) → `BangClient.init` →
`LogonView` (auto-logon) → town view → game via `PlayerService`. A successful dev session
needs: `./gradlew deploy` outputs in `build/client`, staged assets, the server up on 47624,
and a user in `ooouser.users` (see README for setup including the admin-token grant).
