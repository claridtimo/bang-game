# jME3 cutover campaign

The execution plan for Phase 5's **atomic** step: move the running game off the vendored 2005-era
jME fork + LWJGL2 + libGDX onto jMonkeyEngine 3.9.0 + LWJGL3. Unlike the pre-cutover groundwork
(migration map, model loader, board converter, BUI seam — all merged to master), this work
**breaks the client until it lands**, so it runs as ordered phases on the single `JRECutover`
branch with compile/runtime checkpoints — not parallel worktrees that can't compile-verify against
a half-migrated base.

Steering docs (on master): `docs/jme3-migration-map.md` (the per-class DIRECT/ADAPT/REBUILD/DROP
table — the authority for every type move), `docs/jme3-model-loader.md`, `docs/jme3-board-conversion.md`,
`docs/jme3-bui-host.md`, `docs/engine-notes.md`.

## What already exists (reuse, don't rebuild)
- **Runtime model loader** — `tools/j3o-converter` `BangModelLoader`/`ModelConverter` load `model.dat`
  → jME3 `Spatial` (geometry, anim, skinning). Phase 3 wires it into `ModelCache`.
- **Board/bounty Narya format** — `tools/board-converter` converts the 364 fork-Savable
  `.board`/`.game` files to Narya streams; the runtime read-path swap is ~4 methods (see its doc §5).
- **BUI jME3 backend** — `bui/.../backend/Jme3RenderBackend`+`Jme3ImageBacking`, proven to render by
  `tools/jme3-host`. Phase 3 installs it and adds the input path.

## Cutover seam
The LWJGL2→LWJGL3 switch is a single atomic flip at the app-host boundary
(`BangDesktop`/`BangApp`/`EditorDesktop`). It **drags sound with it**: retiring gdx removes the
OpenAL device owner, so the vendored `com.threerings.openal` package moves to LWJGL3/jME3 audio in
the same phase. `com.jme.*` and `com.jme3.*` can coexist at compile time, so lower modules can be
migrated before the host flips — but nothing renders until Phase 3.

## Phases (sequential on `JRECutover`)

### Phase 1 — Build foundation + `app`/`bui` type migration
- Add jme3-core/jme3-lwjgl3/jme3-desktop/jme3-effects/jme3-terrain (root `ext jme3Version`) to the
  modules that will need them; begin retiring the `jme` fork module.
- Migrate `app` (`com.threerings.jme.*`) and `bui` from `com.jme.*` to `com.jme3.*` per the map:
  math/bounding (DIRECT renames), `Spatial`/`Node`/`Geometry`, `Controller`→`Control`,
  render-state objects → `Material`. BUI's seam already abstracts rendering; finish its type-token
  swap (`Renderer`→`RenderManager`, fork `ColorRGBA`→jME3, `BImage extends Quad`).
- **Checkpoint:** `app` and `bui` compile against jme3-core (game not yet runnable).

**Status (2026-06-13): `bui` DONE; `app` NOT STARTED (blocked on the model-pipeline rebuild).**
`./gradlew :bui:compileJava` passes against jme3-core with the fork `jme` module off bui's
compileClasspath (only jme3-core + gdx remain; gdx stays as a GL-free keycode-constant source
until Phase 3). jME3 is now BUI's only render backend: `BackendProvider` requires an explicit
`Jme3RenderBackend` install (it needs an `AssetManager`, so no lazy default), the fork backend
(`JmeRenderBackend`/`JmeImageBacking`) is deleted, the `BImageBacking` seam takes a jME3
`com.jme3.texture.Image`, `BImage` no longer extends the fork `Quad` (builds an ABGR8/BGR8 jME3
Image from its AWT raster), and `BRootNode` no longer extends the fork `Geometry` (the fork scene
hooks became engine-neutral `updateRootState(float)` + `renderWindows(RenderManager)`). `BGeomView`
is retyped to jME3 `Spatial` with its 3D render deferred to a Phase-3 ViewPort; `BCursor` keeps its
`BufferedImage` API with the native install deferred to Phase 3; `PolledRootNode` (fork/LWJGL2/gdx
input glue) was deleted in favour of the Phase-3 `Jme3RootNode`. The `tools:jme3-host` proof harness
was updated to the new API and still compiles.

`app` was deliberately **not** migrated: the `app` module compiles atomically and its model
framework (`Model`/`ModelMesh`/`SkinMesh`/`ModelNode` + controllers, ~4.5k LOC, plus
`CompileModelTask`) is the per-class table's REBUILD risk #1 — `TriMesh`→`Mesh`/`Geometry`,
batches→`VertexBuffer`s, `GLSLShaderObjectsState` hardware skinning → jME3 anim
(`SkinningControl`/`Armature`) or a custom skinning MatDef, and re-emission of the embedded
`SpriteEmission` Savables. That is a design-bearing multi-week rebuild, not a localized type swap,
and there is no independently-compilable slice of `app` to land first (the model package, `JmeApp`
host loop, and `ShaderCache` all sit on the same compile unit). Per the campaign's "stop at a
genuine wall rather than thrash" guidance, `app` is left for a dedicated model-pipeline rebuild
pass. Note `tools/j3o-converter`'s `ModelConverter` already reads the fork model format into jME3
spatials and should be the reference / shared code for that rebuild.

### Phase 2 — `client/shared` migration (the bulk: 164 fork-using files)
- Drive off the map's per-class table: scene graph, render-state→Material across all sites,
  picking/intersection, camera, the `*Sprite`/effect framework, `BangBoardView`/`WaterNode`/sky.
- Regenerate nothing; this is hand work. **Checkpoint:** `client:shared` compiles against jme3.

### Phase 3 — Atomic app-host cutover (the flip)
- Rewrite `BangDesktop`/`BangApp`/`EditorDesktop` as a jME3 `SimpleApplication` (LWJGL3 context).
- `Jme3RootNode` + `RawInputListener` → BUI input; install `Jme3RenderBackend`.
- Move `com.threerings.openal` to LWJGL3/jME3 audio; drop gdx + LWJGL2 + the vendored-for-LWJGL2 copy.
- Wire `BangModelLoader` into `ModelCache`; switch board/bounty load to the Narya format (run the
  board-converter once to produce the shipped `.nboard`/`.ngame`).
- **Checkpoint:** client launches a jME3 window and reaches the login/town view.

### Phase 4 — Board renderer + effects
- Terrain splatting, water reflection, skybox; re-author the 60 `particles.jme` as jME3
  `ParticleEmitter` params. **Checkpoint:** a board renders in-game.

### Phase 5 — Editor + visual regression
- `bangeditor` on a jME3 AWT canvas; per-town visual regression against pre-cutover screenshots.

### Phase 6 — Cleanup
- Delete the `jme` fork module, gdx, LWJGL2 deps, and the cutover scaffolding. Update CLAUDE.md
  and engine-notes for the new stack.

## Acceptance
Client + editor run on jME3 3.9 / LWJGL3 with gameplay and per-town visuals preserved
(the Phase 3 acceptance bar from UPGRADE_PLAN, re-verified on the new engine).
