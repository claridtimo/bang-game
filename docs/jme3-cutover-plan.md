# Epic 1 — jME3 cutover campaign

_(Part of the [Epics](epics.md) roadmap. This is Epic 1; Three Rings code consolidation is
[Epic 2](epic2-threerings-consolidation.md).)_

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

**Status (2026-06-13): `bui` DONE; `app` DONE — `./gradlew :app:compileJava` passes against
jme3-core with the fork `jme` module off app's main compile path. See the app model-pipeline
decision + execution status below.**
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

`app` was initially recorded as blocked: its model framework (`Model`/`ModelMesh`/`SkinMesh`/
`ModelNode` + controllers, ~4.5k LOC, plus `CompileModelTask`) is the per-class table's REBUILD
risk #1 (fork `TriMesh`/batches/`GLSLShaderObjectsState` hardware skinning + embedded
`SpriteEmission` Savables). The resolution — recorded as the decision below — was to recognise that
this fork-format machinery is **build-time-only** (it compiles XML→`model.dat`; nothing runtime
needs to read `model.dat` once the Phase-3 bake produces `.j3o`), so it was split off app's
fork-free `main` into a `modeltool` source set rather than rebuilt. app `main` is now migrated and
compiles against jme3-core; see the decision + execution status below.

#### app model-pipeline rebuild — architecture decision (2026-06-13)

**Decision: RETIRE app's bespoke model classes + bake `model.dat` → `.j3o` at build time,
behind a thin app-side `Model`/`ModelNode` jME3 facade that localizes Phase-2 churn.**

The investigation that forced this (STEP 0):

- **The fork reader cannot stay on the runtime classpath.** `tools/j3o-converter`'s
  `ModelConverter`/`BangModelLoader` `import com.threerings.jme.model.{Model,ModelMesh,ModelNode,
  SkinMesh}` — i.e. they READ the *fork* model classes (via the fork `BinaryImporter` reading
  `model.dat`) and convert to jME3. They are a fork→jME3 *bridge*, not a fork-free runtime loader.
  The documented runtime loader (`docs/jme3-model-loader.md`) keeps the fork reader on the
  *runtime* classpath. That directly conflicts with the Phase-1 checkpoint (app must compile with
  the fork `jme` module OFF its classpath) — you cannot host that loader inside a fork-free `app`.
- **The 310 `model.dat` are fork-`BinaryExporter` Savable graphs**, regenerated each build by the
  project-owned `CompileModelTask`. So the resolution is to move the fork-format read to
  **build time**: bake `model.dat` → jME3-native `.j3o` once per build, and let runtime `app`
  load `.j3o` through stock jME3 with zero fork dependency. `ModelToJ3o` already does exactly this
  bake on top of the shared `ModelConverter`, corpus-verified 310/310.

So the model classes split three ways:

1. **Build-time (stays fork-coupled, lives in `tools/j3o-converter`):** the fork reader + the
   `ModelConverter`/`ModelToJ3o`/`ModelTextureResolver` bridge. `CompileModelTask`'s role
   **shrinks to staging `model.dat`** (unchanged); a new bake step (`ModelToJ3o`, wired into
   `assets:` in Phase 3) turns each `model.dat` into a sibling `model.j3o`. No runtime fork dep.
2. **Runtime (fork-free, lives in `app`):** a thin **`Model` facade** — a jME3 `Node` subclass
   that wraps the loaded `.j3o` `Spatial` and re-exposes the client-facing API shape
   (`createInstance`/`putClone` → jME3 `clone()`; `startAnimation`/`stopAnimation`/`hasAnimation`/
   `getAnimationNames`/observers → drive the `.j3o`'s `AnimComposer`/`SkinningControl`;
   `getEmissionNode`; `getControllers`; `resolveTextures(TextureProvider)` → re-resolve the
   `bang.textures` user-data via the resolver). This keeps `ModelCache.getModel(...)`'s return
   type and the ~29 client sprite/effect call sites' shape stable, so Phase-2 churn over the model
   API is localized to method-body retargeting, not a type-wide `Model`→`Spatial` sweep.
3. **DELETED outright:** the fork-coupled `ModelMesh`/`SkinMesh`/`ModelSpatial` (TriMesh/batch/
   GLSL-skinning machinery — the `.j3o` carries jME3 `Mesh`+`SkinningControl` instead), and the
   fork serialization (`read`/`write`/`putClone`-over-RenderState) bodies of `ModelNode`/`Model`.
   The procedural controllers (`Rotator`/`Translator`/`Billboard`/`Texture*`) become small jME3
   `AbstractControl`s; the emission controllers (`EmissionController` + game `*Emission`) are the
   effects port (Phase 4/§3.3 of the loader doc) and stay client-side.

Why not (a) pure RETIRE (ModelCache returns a bare jME3 `Spatial`): it pushes a type-wide
`Model`→`Spatial` rewrite plus an anim-API rewrite into ~29 Phase-2 files with no compile anchor.
Why not (b') keep the runtime fork reader + convert at load: it violates the fork-off-classpath
checkpoint and ships LWJGL2-era fork code into the LWJGL3 runtime. The chosen split is the only
one that (i) reuses the corpus-verified converter unchanged, (ii) gets app fork-free, and
(iii) keeps the client API shape for Phase 2.

#### app Phase-1 execution status (2026-06-13)

**Checkpoint passing command:** `./gradlew :app:compileJava` → BUILD SUCCESSFUL, fork `jme`
verified off app's `compileClasspath` (only `jme3-core` + the gdx keycode source from bui remain).
`./gradlew :app:compileModeltoolJava` also passes (the relocated fork build-time compiler).

**Module split implemented.** app gained a `modeltool` source set (`app/src/modeltool/java`,
`modeltoolImplementation project(":jme")`, off `main`'s classpath). Moved there, fork-format and
build-time-only, relocated to package `com.threerings.jme.tools.*`:
- the model compiler: `tools/{CompileModelTask,CompileModel,ModelDef,AnimationDef,BuildSphereMap,
  BuildSphereMapTask}`, `tools/xml/{ModelParser,AnimationParser}`;
- the fork model classes: `tools/model/{Model,ModelNode,ModelMesh,SkinMesh,ModelSpatial,
  ModelController,BillboardController,EmissionController,Rotator,Translator,TextureController,
  TextureAnimator,TextureTranslator,TextureProvider}`;
- the fork-only util: `util/{ShaderCache,ShaderConfig,BatchVisitor}` + fork-typed copies of
  `util/{JmeUtil,SpatialVisitor}` (their jME3 versions live in `main`).
`assets:compileModels`'s ant taskdef classpath was extended with
`project(":app").sourceSets.modeltool.runtimeClasspath`, so the XML→`model.dat` pipeline is
unchanged. **CompileModelTask's role is unchanged** (XML→fork `model.dat`); the *runtime* side
shrinks: nothing runtime reads `model.dat` anymore — the Phase-3 bake (`ModelToJ3o`) turns each
`model.dat` into a sibling `.j3o` that the client `AssetManager` loads natively.

**app `main` migrated onto jME3** (compiles): `util` (JmeUtil/SpatialVisitor/ImageCache —
ImageCache builds jME3 `texture.Image` RGBA8/RGB8), `sprite` (Path→`AbstractControl` + new
`AnimationController` speed/active/repeat seam; Sprite→`addControl`; path subclasses DIRECT),
`camera` (CameraHandler→jME3 `Camera`/`setAxes`/`Plane.getNormal`; GodViewHandler→jME3
`AnalogListener` with a Phase-3 `registerWith(InputManager)`), `effect` (FadeInOutEffect/
WindowSlider→`Geometry`+`Unshaded` Material in `Bucket.Gui`, `AbstractControl` stepping, screen
size passed in), `chat` (ColorRGBA move), and the `JmeContext`/`JmeApp` host seam (retyped to
`AssetManager`/`RenderManager`/`Camera`; `getRenderer`→`getRenderManager`, `getDisplay` dropped;
the live LWJGL3 host loop/input/BUI-install is the Phase-3 flip — `JmeApp` is a jME3-typed
skeleton until then).

**NOT done (left for Phase 2/3, by design):**
- The fork-free app-side **`Model` facade** is *not* written. The decision is to wrap the loaded
  `.j3o` `Spatial` and re-expose the client API, but the loader (`BangModelLoader`/`ModelConverter`)
  is fork-coupled and lives in `tools/j3o-converter`; the facade has no compile anchor until
  `client/shared` is on jME3 (Phase 2) and the `.j3o` bake/load path is wired (Phase 3). Writing
  it now would be speculative against code that cannot yet compile. **It is Phase 2's first task**
  (shapes below).
- The procedural controllers (`Rotator`/`Translator`/`BillboardController`/`Texture*`) and the
  `EmissionController` base remain only in `modeltool` (fork). The jME3 procedural-control
  equivalents are a cheap follow-up; the emission controllers are the effects port (Phase 4).

> **Deferred validation (gated on Phase 2):** `assets:compileModels` — the fork→`model.dat`
> pipeline now driven by app's `modeltool` source set — cannot run until client/shared compiles:
> its taskdef classpath pulls `configurations.tools → :tools → :server → :client:shared`. So the
> model pipeline (and a full `assets:deploy`) is re-verified at the END of Phase 2, not now. This
> was always the case (the tools→server→client chain predates the cutover); it just surfaces now
> because client/shared is mid-migration.

### Phase 2 — `client/shared` migration (the bulk: 164 fork-using files)

**STATUS (2026-06-13): DONE.** `:client:shared:compileJava`, `:server:compileJava`, and `:assets:compileModels` (310/310 model.dat) are all GREEN; zero fork imports remain outside the deferred-to-Phase-3 vendored `com.threerings.openal` package. `:client:desktop` fails only on the 2 gdx-host-wiring errors (BangDesktop/EditorDesktop) = the Phase-3 flip. Done via a foundation pass + 5 parallel fan-out slices + an integration drive-to-green (50 commits).

**Concrete starting point (the app `Model` facade + the seam edits app's migration forces):**

1. **Write the app `Model` facade** (`com.threerings.jme.model`, fork-free, in app `main`): a jME3
   `Node` that wraps the `.j3o`-loaded content + its `AnimComposer`/`SkinningControl`, re-exposing
   the surface client uses (measured): `getEmissionNode` (12), `getProperties` (8), `getAnimation`
   (7, returns an `Animation` carrying `frameRate` + `getDuration()`), `pauseAnimation` (5),
   `getControllers` (4), `startAnimation`/`stopAnimation`/`resolveTextures`/`createInstance` (3 each
   — `createInstance`/`putClone`→jME3 `clone()`; `resolveTextures(TextureProvider)` re-resolves the
   `bang.textures` user-data the converter preserves), `lockInstance`/`hasAnimation` (2 each),
   `setAnimationMode`/`reverseAnimation`/`getVariantNames`/`fastForwardAnimation`/`createPrototype`
   (1 each). Nested API shapes used: `Model.Animation` (`.frameRate`, `.getDuration()`),
   `Model.CloneCreator` (passed to emission controllers' `putClone`), `Model.AnimationMode`,
   `Model.AnimationObserver`. `ModelCache.getModel(...)` keeps returning this `Model`.
2. **Re-point `client/util/ModelCache`** at `assetManager.loadModel(".j3o")` → wrap in the facade;
   thread `variantIndex`/`Colorization[]`/detail into `ModelTextureResolver` (the indirection shape
   exists in tools/j3o-converter — see docs/jme3-model-loader.md §5).
3. **Edits app's `main` migration forces on client (the seam churn this commit created):**
   - `SpatialVisitor<ModelMesh>` sites (`ModelCache`, `effect/HeroInfluenceViz`,
     `effect/IronPlateViz`) → `SpatialVisitor<Geometry>` (the `.j3o` yields jME3 `Geometry`, not
     `ModelMesh`).
   - `JmeContext` consumers: `getRenderer()`→`getRenderManager()` (93 sites), `getDisplay()`
     removed (31 sites — re-point to `getCamera()`/`getAssetManager()` or a screen-size accessor);
     `getInputHandler()` removed (12 sites — Phase 3 input). Implementers `BangApp`/`EditorApp`/
     `BasicContext` re-fit to the new seam.
   - `IconConfig`/`ParticleUtil` fork `Controller.RT_*` → `com.threerings.jme.util.JmeUtil.RT_*`.
   - `FadeInOutEffect`/`WindowSlider` constructors now take `AssetManager`/screen-size (were
     `DisplaySystem`-global); their callers (`WindowFader` etc.) pass them through `BasicContext`.
   - `GodViewHandler` is now an `AnalogListener`; `GameInputHandler`/`EditorController` wire it via
     `registerWith(InputManager)` at the Phase-3 host.

Then proceed with the rest of the per-class table (scene graph, render-state→Material across all
sites, picking, camera, the `*Sprite`/effect framework, `BangBoardView`/`WaterNode`/sky).
- Drive off the map's per-class table: scene graph, render-state→Material across all sites,
  picking/intersection, camera, the `*Sprite`/effect framework, `BangBoardView`/`WaterNode`/sky.
- Regenerate nothing; this is hand work. **Checkpoint:** `client:shared` compiles against jme3.

#### Phase 2 FOUNDATION pass — DONE (2026-06-13): what landed + the fan-out assignment

The shared foundation + the mechanical bulk landed on `JRECutover` (4 commits). `client:shared`
does **not** compile (expected — single compile unit; the REBUILD clusters below remain). What is
done and compile-clean:

- **app `Model` facade** (`app/.../jme/model/Model.java`, fork-free) — a jME3 `Node` wrapping the
  `.j3o` content + its `AnimComposer`/`SkinningControl`, re-exposing the full measured client
  surface (`getEmissionNode`/`getProperties`/`getAnimation`/`start|stop|pause|reverse|
  fastForwardAnimation`/`getControllers`/`createInstance`/`createPrototype`/`lockInstance`/
  `hasAnimation`/`setAnimationMode`/`getVariantNames`/`resolveTextures`) + nested
  `Animation`(`.frameRate`,`getDuration()`)/`CloneCreator`/`AnimationMode`/`AnimationObserver`.
  **`:app:compileJava` is GREEN.** `ModelTextureResolver` was promoted from `tools/j3o-converter`
  into app `main` (`com.threerings.jme.model`) as the single home shared by `ModelCache` and the
  converter; a fork-free `TextureProvider` seam (`getTexture(Geometry, name)→Texture`) was added;
  the converter now writes `bang.frameRates` user data so the facade re-exposes `frameRate`.
- **`ModelCache`** re-pointed at `assetManager.loadModel(type+"/model.j3o")` wrapped in the facade;
  fork VBO/display-list/shader bookkeeping deleted (jME3 owns it); variant/colorization threaded
  through `ModelTextureResolver`. Returns the facade `Model` (call-site type stable).
- **Seam edits applied:** `ctx/_ctx.getRenderer()→getRenderManager()` (~80 context sites);
  `SpatialVisitor<ModelMesh>→<Geometry>` (ModelCache, IronPlate/HeroInfluence viz, TreeBedSprite);
  fork `Controller.RT_*→JmeUtil.RT_*`; `ColorRGBA.white/black/...→.White/.Black/...` (jME3
  capitalizes; ~100 sites); `FadeInOutEffect`/`WindowSlider` callers thread
  `getAssetManager()`/`getCamera().getWidth()/getHeight()`; `BasicContext.loadParticles` Spatial
  type-token → jME3.
- **Mechanical DIRECT renames** (104 files): math (`Vector*/Quaternion/Matrix*/FastMath/Ray/
  Plane`), `ColorRGBA` (renderer→math), `bounding.*`, `scene.Node`, `geom.BufferUtils`→
  `util.BufferUtils`. **BUI render-hook param `Renderer→RenderManager`** in 33 view/icon/background
  files whose only fork dep was overriding `render*(Renderer)` (NOT rebuild — BUI's hook type).

Result: fork-importing files in client/shared dropped 132→**99**. The remaining 99 are the
parallel fan-out. javac caps at 100 errors so the assignment is keyed on remaining fork imports
(the true work surface), not the truncated error count.

**FAN-OUT ASSIGNMENT (remaining-error breakdown by subsystem, with the dominant fork API each
cluster still needs).** All paths under `client/shared/.../com/threerings/bang/`.

1. **`*Sprite`/sprite framework — 31 files** (`game/client/sprite/`). Dominant fork API:
   `scene.Spatial`(ADAPT: setRenderState→Material, queue/cull hints, draw hooks gone),
   `scene.state.{TextureState,LightState,MaterialState}`→**Material** (REBUILD),
   `scene.Controller`→`control.AbstractControl` (Path/Spinner/Bouncer + the `*Emission`),
   `scene.SharedMesh`→shared-Mesh Geometry, `scene.BillboardNode`→`BillboardControl`,
   `scene.shape.Quad`→Mesh, `image.Texture`→`texture.Texture2D`,
   `util.export.*`(Savable embedded in models — the emission Savables), `jmex.effects.particles.*`
   →`ParticleEmitter`. Files: ActiveSprite, MobileSprite, PieceSprite, UnitSprite, PropSprite,
   BonusSprite, BreakableSprite, CowSprite, BisonSprite, OneArmedBanditSprite, WendigoSprite,
   ViewpointSprite, MarkerSprite, SafeMarkerSprite, CounterSprite, GenericCounterNode,
   PieceTarget, PieceStatus, UnitStatus, ShotSprite, FireworksSprite, TreeBedSprite, Spinner,
   Bouncer, and the emission controllers (FrameEmission, GunshotEmission, MisfireEmission,
   DudShotEmission, SmokePlumeEmission, ParticleEmission, TransientParticleEmission). **Theme:
   render-state→Material + Controller→AbstractControl + particles.** The `*Emission` classes are
   the effects port (Phase 4) and code their `putClone(Controller, Model.CloneCreator)` /
   `resolveTextures(TextureProvider)` against the facade's now-defined `CloneCreator`/
   `TextureProvider`.
2. **Effect viz — 13 files** (`game/client/effect/`). Dominant: `scene.Controller`(REBUILD→
   AbstractControl), `jmex.effects.particles.*`(REBUILD→ParticleEmitter), render-state→Material,
   `scene.BillboardNode`, `scene.shape.Quad`. Files: ExplosionViz, RepairViz, WreckViz,
   HealHeroViz, DamageIconViz, IconViz, IconInfluenceViz, HeroInfluenceViz, IronPlateViz,
   NoncorporealViz, ParticleEffectViz, ParticleInfluenceViz, ParticlePool. **Theme: particle
   re-author + render-state→Material overlay.**
3. **Board renderer + sky/water/terrain (the REBUILD critical path) — 5 files**
   (`game/client/`): `BoardView` (picking: `intersection.PickResults/TrianglePickResults`→
   `collideWith`/`CollisionResults`; lights; TextureRenderer), `BangBoardView`, `TerrainNode`
   (splat combine→custom MatDef), `WaterNode` (reflection FBO + GL-thread init), `SkyNode`
   (Dome + gradient). **Theme: render-state→Material, multi-texture splat MatDefs, RTT→FrameBuffer,
   picking→collideWith.** Highest fidelity risk (map §4 risks #3/#4).
4. **Game handlers + in-game views — 14 files** (`game/client/`): EffectHandler,
   BallisticShotHandler, RocketHandler, HoldHandler, RobotWaveHandler, AreaDamageHandler,
   WendigoHandler, GameInputHandler (`input.InputHandler`→Phase-3 InputManager/AnalogListener),
   GameCameraHandler (`renderer.Camera` ADAPT), GridNode (`scene.Line`→Lines Mesh), BangView,
   PlayerStatusView, HeroBuildingView, BountyGameOverView. **Theme: Camera/Spatial ADAPT + a few
   render-state sites; GameInputHandler is the Phase-3 input seam (`GodViewHandler.registerWith`).**
5. **Game data Savables — 10 files** (`game/data/`, `game/util/BoardFile`): `util.export.*`
   (Savable/InputCapsule/OutputCapsule/JMEImporter/JMEExporter) + `binary.BinaryImporter/Exporter`.
   Files: Piece, Prop, Marker, Track, Viewpoint, BangBoard, BoardData, BangConfig, Criterion,
   BoardFile. **Theme: `com.jme.util.export.*`→`com.jme3.export.*` (ADAPT, case rename JmeImporter/
   JmeExporter) for the in-memory classes; the on-disk `.board`/`.game` migration is the offline
   board-converter (Phase 3), NOT a code change here.** Mechanical-ish but touches the wire/save
   format — pairs with the board-converter doc.
6. **Central render-state factory + RTT + particle/texture caches — 9 files**: `util/RenderUtil`
   (587-ln state factory → shared-**Material** library — gates clusters 1-3), `util/IconConfig`,
   `util/ParticleUtil`, `util/BackTextureRenderer` (`renderer.TextureRenderer`→FrameBuffer),
   `client/util/{TextureCache,TexturePool,ParticleCache,ResultAttacher}` (`image.{Texture,Image}`,
   `util.TextureKey/TextureManager`, `scene.state.gdx.records.TextureStateRecord` DROP,
   `jmex.effects.particles.*`). **Theme: the render-state→Material library + texture/particle
   loading off fork TextureState onto `Texture2D`/AssetManager.** RenderUtil is the keystone —
   do it first; clusters 1-3 draw from it.
7. **Core client/views — 8 files** (`client/`): BangApp, BasicClient (`renderer.Renderer` host
   wiring → Phase-3), BangUI, BangPrefs, GlobalKeyManager, OptionsView, TownView,
   `client/bui/WindowFader` (`scene.Controller`→AbstractControl). **Theme: mostly host-seam
   (`DisplaySystem`/`InputHandler` removal, Phase-3) + a couple Controller/Renderer sites.**
8. **Editor — 3 files** (`editor/`): EditorBoardView, CameraDolly (`renderer.Camera`),
   CrossStatus (`scene.Line`/`SharedMesh`), + `jmex.terrain.util.*` heightmaps (ADAPT→jme3-terrain),
   `scene.state.WireframeState`→`material.RenderState.setWireframe`, `geom.Debugger`. **Editor-only,
   Phase-5 deferrable.**
9. **Misc place views — 6 files**: `ranch/client/UnitView` (RTT portrait), `saloon/client/PaperView`,
   `chat/client/SystemChatView`, `bounty/client/BountyGameEditor`, `bounty/data/{RankCriterion,
   IntStatCriterion}` (Savable). **Theme: a few render-state/Savable sites; small.**

**Facade/seam API the fan-out codes against (decisions to honour):**
- `com.threerings.jme.model.Model` (app) is the model handle `ModelCache` returns; it is a jME3
  `Node`. `getEmissionNode()→Node`, `getControllers()→List<com.jme3.scene.control.Control>` (the
  effects port populates this), `getAnimation(name)→Model.Animation` (`.frameRate` int +
  `getDuration()` float). `createInstance()/createPrototype(variant)` clone the content.
- `com.threerings.jme.model.TextureProvider` is **fork-free**: `Texture getTexture(Geometry, String)`
  (NOT the fork `TextureState getTexture(String)`). Emission controllers' `resolveTextures` recode
  to this. `ModelTextureResolver` (app `com.threerings.jme.model`) is the shared re-resolution
  engine; `textureAssetPath(typePath, name)` is public.
- `JmeContext`/`BasicContext` seam: `getRenderManager()` (not `getRenderer()`), `getAssetManager()`,
  `getCamera()` (jME3 `Camera`, has `getWidth()/getHeight()` for screen size — there is **no**
  `getDisplay()` and **no** `getInputHandler()`). Input is Phase-3 (`registerWith(InputManager)`).
- `JmeUtil.RT_{CLAMP,WRAP,CYCLE}` replace fork `Controller.RT_*`.
- `FadeInOutEffect`/`WindowSlider` ctors take `AssetManager` + screen `width,height` up front.

### Phase 3 — Atomic app-host cutover (the flip)
- Rewrite `BangDesktop`/`BangApp`/`EditorDesktop` as a jME3 `SimpleApplication` (LWJGL3 context).
- `Jme3RootNode` + `RawInputListener` → BUI input; install `Jme3RenderBackend`.
- Move `com.threerings.openal` to LWJGL3/jME3 audio; drop gdx + LWJGL2 + the vendored-for-LWJGL2 copy.
- Wire `BangModelLoader` into `ModelCache`; switch board/bounty load to the Narya format (run the
  board-converter once to produce the shipped `.nboard`/`.ngame`).
- **Checkpoint:** client launches a jME3 window and reaches the login/town view.

### Phase 4 — Board renderer + effects
Split into sub-phases as the work fell out (checkpoint: a board renders in-game with fidelity
approaching the fork baselines in `baseline/fork-before/`):
- **4a — DONE:** multi-texture terrain splat (custom `TerrainSplat.j3md`), board background/fog
  color to the host ViewPort, + two real bugs fixed along the way: BUI textured-image channel
  scramble (the pink UI cast — ABGR8 endianness) and instant idle-out (`Jme3RootNode` tick stamp
  uninitialized → passive clients logged out in ~10s). Units, fences/small props, combat-effect
  icons, shadows, and the HUD render correctly.
- **4b — DONE (non-bug):** investigated the untextured-white-buildings report; the bake/resolve/
  render path is actually correct — 272/272 baked building geometries carry a bound DiffuseMap +
  normals, and the live frontier-town view (`baseline/jme3-phase4/building-town-after.png`) shows
  all buildings fully textured (brown wood, signs, windows), matching the fork baseline. The white
  shot was stale pre-4a state. Deliverable: a headless **`RenderToPng`** offscreen harness (no
  window; LWJGL3 FrameBuffer through the real load/resolve/clone path) — the Phase-7 #1 tool, seeded
  early. Caveats: the offscreen harness's own lighting has a blue tint to calibrate (cosmetic,
  harness-only); the in-game *game* board (vs town view) wasn't re-captured headlessly (autoplay
  doesn't drive BUI big-shot selection) — high confidence it's fine given same `BoardView` renderer.
- **4c — DONE:** water Fresnel sphere-map reflection verified and fixed. The shader/material
  approach is correct (animated, reflective, sphere-environment-mapped — reproduces the fork's
  EM_SPHERE water<->sky Fresnel blend), confirmed by a headless offscreen render of the actual
  `BangWater.j3md` material + the same Fresnel sphere map + a wave-displaced surface (new
  `tools/j3o-converter` `RenderWaterToPng` harness; `baseline/jme3-phase4/water-{before,after}.png`).
  **Root-cause bug fixed:** the Fresnel sphere map was tagged `ColorSpace.sRGB`, which the
  gamma-correct live pipeline (jME3 default `GammaCorrection=true`, inherited by `BangDesktop`)
  sRGB-decodes on sample, crushing the baked water/sky colors to a dark, near-uniform sheet (the
  classic "flat solid color" water failure). The fork displayed these baked colors verbatim with no
  gamma management, so the sphere map is now tagged `ColorSpace.Linear` in `WaterNode.refreshColors`
  — the surface renders at full color with the reflective variation reading as water. Animation is
  confirmed (the FFT-driven normals change each frame; two harness frames diff across the whole
  surface). Also fixed the Phase-4b harness blue-tint caveat: it was an RGBA-vs-BGRA channel swap in
  the framebuffer readback (`Screenshots.convertScreenShot` assumes BGRA) plus a missing sRGB encode
  on the offscreen color target — `RenderWaterToPng` reads RGBA directly into the image and uses an
  sRGB color target, so its colors are now neutral/correct.

  **LIVE-BOARD VERIFICATION (2026-06-13, this pass):** the water was rendered on a real shipped
  `.board` through the full live client+server (not the harness) and visually confirmed to match
  the fork reference (`baseline/fork-before/water.png`): saturated, reflective, reads-as-water teal.
  Board used: **Thunderbird Rock** (`data/boards/indian_post/4/thunderbird_rock.board`, water
  `#003232`, sky-overhead `#00FF99`, ~65% of tiles below the water plane — a coastal board like the
  fork shot). Reached via `bangserver indian_post` + autoplay client `-Dboard="Thunderbird Rock"
  -Dtest=true -Dautoplay=true`. Live render shows teal coastal water around a sandy island with
  rope bridges; sampled live water avg `#347F7E` vs the fork shot's `#255245` — same saturated
  green-teal hue family (different specific board, so not pixel-identical, but the fidelity bar —
  saturated/reflective/animated, not pale-flat or dark-muddy — is met). Deliverables:
  `baseline/jme3-phase4/water-live-{before,after}.png` (+ `water-live-after-frame2.png`). **No code
  tuning was needed** — the prior sRGB->Linear sphere-map fix produces correct color on a real board;
  this pass only verified it. `refreshColors` water/sky colors are board-driven (`getWaterColor()`/
  `getSkyOverheadColor()`), identical to the fork, so any board's stored teal reproduces verbatim.

  **Two non-water findings from this pass:**
  - *Phase-3 board read is HEALTHY.* The live client+server read shipped `.board` files (now pure
    Narya streaming) with zero errors — server "Loading boards..." clean, client "Downloading board"
    + full board render. A standalone scan of all 165 boards via `BoardFile.loadFrom` also read every
    `BangBoard` (incl. water fields) fine. (A bare scan hits a `Prop.init` NPE only when the
    `PropConfig` registry isn't initialized — a harness-classpath artifact, not a format bug; it
    vanishes with the staging `rsrc/` on the classpath, as the live client/server have.)
  - *`bangserver indian_post` registered NO indian_post boards* until fixed: `etc/test/
    server.properties` left `indian_post.town_id` commented out, so `ServerConfig.townId` fell back
    to `frontier_town` (townIndex 0) and `BoardManager.mapBoard` skipped every board with
    `getMinimumTownIndex() > 0` (i.e. all indian_post boards) → `getBoard()` returns null →
    `BangManager.startRound` NPE at `_rounds[roundId].board.name`. Set `indian_post.town_id =
    indian_post` (local `etc/test/`, gitignored) to serve indian_post boards on that node. Frontier
    boards were unaffected throughout.
- **4d — DONE (test tooling):** `bin/devtest` — a standard live-run "run sheet" that ends the
  recurring setup tax behind most agent run failures. It (1) kills stale Bang processes + frees
  47624, (2) verifies MySQL up and `bang`/`yeehaw` connects, (3) `./gradlew deploy` (green;
  `--no-deploy` to skip), (4) starts the server and **polls the log until "Server listening"**,
  (5) launches the client with standard flags (muted by default; `-test`/`--board` `-autoplay`
  creds), (5b) **clicks into the window via python-xlib `warp_pointer`+XTest — REQUIRED to start the
  autoplay game** (without a click you only ever capture the static pre-game screen; the #1 thing
  that was silently tripping agents), (6) optional `--shot` screenshot, (7) trap-based teardown that
  waits for process death (with `-9` escalation) and frees the port. Validated end-to-end
  (cleanup→mysql→server-wait→click→autoplay board→shot→clean teardown). Two gotchas learned and
  baked in: match the game window on **"howdy"** (not "bang" — that hits a "bang-game" terminal
  title) and click via window-relative `warp_pointer` (root-coord `translate_coords` returns bogus
  negatives on this multi-monitor display). Runbook: `docs/running-the-game.md` — every future "run
  the app" agent brief points at it. Pairs with the Phase-5 offscreen harnesses
  (`RenderToPng`/`RenderWaterToPng`) for deterministic windowless model/water shots.
_(Phase 4 core rendering is done at 4a–4d. The remaining fidelity items moved out to **Phase 6**:
particles → 6a, live-render/input defects → 6b — they follow the Phase-5 testing harnesses, which
accelerate diagnosing them.)_

### Phase 5 — Agent testability (brought forward — ahead of the fidelity/editor work)

Brought forward (was originally last): these harnesses directly accelerate the Phase-6 fidelity
defects and the Phase-7 editor regression, so they come first. This is a real-time 3D OpenGL
networked game — the hard part of agent-driven work is that an agent can neither reliably *see* the
rendered output (X-grabs off `DISPLAY=:1` are flaky) nor deterministically *drive* gameplay; there is
no Playwright equivalent (an OpenGL framebuffer has no DOM). `bin/devtest` (4d) already covers the
live-client path. JDK 25 and a JUnit 4→5 bump do **not** help (JDK 21 already has JFR + helpful NPEs;
the bottleneck is coverage of a hard-to-assert app, not the runner). The two harnesses to finish:

1. **Headless offscreen render-to-PNG (highest value; seed already built).** Generalize the existing
   `tools/j3o-converter` `RenderToPng`/`RenderWaterToPng` into a board/scene/model render-to-PNG that
   runs windowless on a `FrameBuffer`, so agents diff deterministic snapshots against
   `baseline/fork-before/` instead of flaky X-grabs. Feeds CI visual regression and per-change checks
   (incl. the Phase-6 sprite defects + the Phase-7 editor regression).
2. **Headless Narya bot client + server-side dobj assertions.** A rendering-free client that logs in,
   drives gameplay through the service API, and asserts on distributed-object state — the closest
   thing to "Playwright for this game." Leans on the existing AI/`-autoplay`/bot + the Narya wire.

Supporting, lower priority: fold the converter harnesses (j3o 310/310, board 364/364) + the 3 unit
tests into a JUnit + CI gate (AssertJ for ergonomics; JUnit 5 optional, not the point); JFR for
frame-timing/perf telemetry. Not worth doing for tooling reasons: JDK 25, the JUnit major bump.

### Phase 6 — Remaining fidelity & polish

Reproduce/diagnose with the Phase-5 harnesses + the `bin/devtest` run sheet.

**6a — particles.** The 60 `particles.jme` are still a degraded single-blob; re-author as jME3
`ParticleEmitter` params (combat-effect icons already render). The `*Emission` controllers exist as
jME3 `AbstractControl`s from Phase 2, awaiting real emitters.

**6b — remaining live-render & input defects** (observed on live boards 2026-06-13; cluster-1/3
fidelity + Phase-3 input):

- **Claim/counter numbers render sideways (rotated ~90°).** The floating gold-claim nugget count is a
  camera-facing billboard. `GenericCounterNode.createGeometry` (and `CounterSprite`) now do
  `bbn.addControl(new BillboardControl())` with the **default alignment** (jME3
  `BillboardControl.Alignment.Screen`). The fork used `BillboardNode` (SCREEN_ALIGNED) on a **Z-up**
  board scene graph (the counter is translated along +Z = up). jME3's `Screen`/`Camera` billboard
  alignment assumes **Y-up** for the billboard's up vector, so the text quad's up-axis ends up
  perpendicular to screen-up → sideways glyphs. Fix: set the alignment / billboard up-vector to match
  the board's Z-up convention (try `Alignment.Camera`, and/or pre-rotate the count `Quad` so its
  texture-up maps to the board's up), then re-check against the fork screenshots. Files:
  `game/client/sprite/GenericCounterNode.java:54`, `game/client/sprite/CounterSprite.java:107`. Same
  default-`BillboardControl` pattern also appears in `PieceTarget`, `OneArmedBanditSprite`, and the
  effect viz (`IconViz`/`IconInfluenceViz`/`HeroInfluenceViz`) — audit all of them for the same
  up-axis issue while here. (This is the visual side of migration-map risk row "BillboardNode→
  BillboardControl: alignment enums map".)
- **Mounted/horse big-shot character model renders incorrectly.** The horse (mounted unit big-shot)
  model is wrong on the live board — needs a closer look to classify: skinning/anim (SkinningControl
  / AnimComposer mis-bind from the `.j3o` bake), a wrong variant/clone via the Phase-2 `Model` facade
  `createInstance`/`resolveTextures` path, or a bad mesh in the `model.dat`→`.j3o` conversion. Start
  by loading the offending unit through `ModelCache` in the `RenderToPng` harness and comparing to the
  fork. Likely lives in the model-pipeline cluster (app `Model` facade + `tools/j3o-converter`
  `ModelToJ3o`), not in board rendering. Capture which specific unit (e.g. a frontier_town/indian_post
  mounted big-shot) when reproducing.
- **Shotgun unit sprite renders incorrectly** (observed live, 2026-06-13). The "shotgun dude" unit
  (the shotgunner) sprite is wrong on the board. Same model-pipeline-vs-sprite classification needed
  as the horse item — diagnose via `RenderToPng` on that unit's model through `ModelCache` and compare
  to the fork; check the unit's `model.dat`→`.j3o` bake (mesh/skin/anim) and its `UnitSprite`/
  `ActiveSprite` material/animation binding. Probably shares a root cause with the horse big-shot.
- **WASD camera panning doesn't work at all** (functional, not cosmetic). `GodViewHandler` is a jME3
  `AnalogListener`, but its `registerWith(InputManager)` was deferred at the Phase-3 host flip and is
  never called, so keyboard pan/zoom never reaches the camera. Wire it in the jME3 host
  (`BangApp`/`GameInputHandler`). Until fixed, agents can't pan to compose shots — pick boards whose
  default camera frames the subject.

### Phase 7 — Editor + visual regression
- `bangeditor` on a jME3 AWT canvas; per-town visual regression against pre-cutover screenshots,
  driven by the Phase-5 offscreen render harness.

### Phase 8 — Cleanup
- Delete the `jme` fork module and the remaining cutover scaffolding once nothing references it.
- Drop the now-unnecessary deps: bare `gdx` (kept only as a transitional keycode-constant source —
  replace with jME3 keycodes) and any lingering LWJGL2 bits.
- **Revert the dev mute:** `BangPrefs.SILENT` is muted-by-default on this branch (`-Dsound=true` to
  hear) — restore to audible-by-default before ship.
- Remove the now-vestigial jME3 `Savable` impls left on the board/config data classes (they stream
  via Narya now; the Savable side is unused).
- Update CLAUDE.md + docs/engine-notes.md for the new jME3 / LWJGL3 stack.

## Acceptance
Client + editor run on jME3 3.9 / LWJGL3 with gameplay and per-town visuals preserved
(the Phase 3 acceptance bar from UPGRADE_PLAN, re-verified on the new engine).
