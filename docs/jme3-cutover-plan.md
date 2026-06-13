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

## Phase 7 — Agent testability (post-cutover follow-up, not part of cutover acceptance)

Rationale: this is a real-time 3D OpenGL networked game, so the hard part of agent-driven work is
that an agent can neither reliably *see* the rendered output (screen-grabs off `DISPLAY=:1` are
flaky — window focus, timing, the "Idled Out" inactivity timer) nor deterministically *drive*
gameplay. There is no Playwright equivalent: an OpenGL framebuffer has no DOM/semantic tree to
query. JDK 25 and a JUnit 4→5 bump do **not** help here (JDK 21 already has JFR + helpful NPEs; the
test bottleneck is coverage of a hard-to-assert app, not the runner). The leverage is two bespoke
harnesses, both leaning on infrastructure the cutover already produced:

1. **Headless offscreen render-to-PNG harness (highest value).** Render a specific
   scene/board/model to a jME3 `FrameBuffer` → PNG with no window, on demand and deterministically.
   Replaces flaky X-display grabs with scriptable visual snapshots an agent can diff against
   baselines (e.g. `baseline/fork-before/`). Builds on the Phase-3 `ScreenshotAppState` hook + the
   model/board loaders. Enables visual regression in CI and per-change visual verification.
2. **Headless Narya bot client + server-side dobj assertions.** A rendering-free client that logs
   in, drives gameplay through the service API, and asserts on distributed-object state — the
   closest thing to "Playwright for this game." Leans on the existing AI/`-autoplay`/bot
   infrastructure and the Narya wire protocol; verifies game logic with zero graphics.

Supporting, lower priority: fold the converter verification harnesses (j3o 310/310, board 364/364)
and the 3 existing unit tests into a JUnit + CI gate (add AssertJ for ergonomics — JUnit 5 optional,
not the point); `xdotool` for crude live-window input injection; JFR for frame-timing/perf telemetry.
Not worth doing for tooling reasons: JDK 25, the JUnit major bump.

Also a Phase-6 reminder captured here: `BangPrefs.SILENT` is muted-by-default on this branch as a
dev convenience — revert it to opt-in (audible default, `-Dsound` no longer needed) before ship.
