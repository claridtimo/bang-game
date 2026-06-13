# jME3 Migration Map (Phase 5, step 1: inventory & API mapping)

Definitive migration map for porting Bang! Howdy off the vendored 2005/2007-era
jMonkeyEngine fork (`jme/` module, `com.jme.*` / `com.jmex.*`) onto
**jMonkeyEngine 3.9.0-stable** (`com.jme3.*`). Every jME3 target named below was
verified to exist in the reference clone at `../jmonkeyengine` (3.9-era source).

Date: 2026-06-13. All counts measured on branch `jme5/inventory` with grep over
`*.java`; "uses" = number of files importing the class (`^import (static )?<class>;`).

## Headline numbers

| Metric | Value |
|---|---|
| Distinct fork classes imported by Bang code | **93** |
| Files importing the fork (app / bui / client/shared / client/desktop) | **239** (43 / 32 / 164 / 0) |
| Classes in the fork itself (`jme/` module) | 358 (≈190 of them in subsystems Bang never touches) |
| Mapping verdicts | **DIRECT 18 · ADAPT 41 · REBUILD 25 · DROP 10** |
| Legacy jME-binary *data* assets (no XML source) | 168 `.board` + 196 bounty `.game` + 60 `particles.jme` = **424 files** |
| Models (recompiled from source XML each build) | 310 `model.properties` → `model.dat` |

Two corrections to prior docs discovered during this inventory (details in §6):
`.board`/`.game` files are **jME BinaryExporter format, loaded by the server**
(not pure Narya streaming, and boards live in `data/boards/`, not
`assets/rsrc/boards/`); and `CompileModelTask` is project code
(`app/src/main/java/com/threerings/jme/tools/CompileModelTask.java`), not nenya-tools.

## Module roles (who is engine, who is consumer)

```
jme/            358 classes  THE FORK — com.jme.* + com.jmex.{effects,terrain,awt}.
                             Replaced wholesale by jME3 artifacts; nothing here is kept
                             except (option) the legacy binary reader (§5.2).
app/             56 classes  com.threerings.jme.* — Three Rings' OWN framework on top of
                             the fork (JmeApp loop, camera handlers, sprites, the model
                             format + compiler). 43 files import the fork. Ported as
                             custom code ON TOP of jME3, not mapped.
bui/             88 classes  com.jmex.bui — BUI is PROJECT-OWNED despite the package name
                             (it is not part of the engine fork). Its public API survives;
                             its render internals (32 fork-importing files, 12 files with
                             raw GL11 calls) are rebuilt on jME3.
client/shared/  758 classes  Game code; 164 files import the fork.
client/desktop/              0 fork imports (gdx/LWJGL launchers only).
server/                      loads .board/.game via BoardFile → fork BinaryImporter —
                             the SERVER depends on the fork's export subsystem.
```

## 1. Fork API surface actually used

Grouped by jME subsystem. "uses" = importing files (app+bui+shared). Bang-side
dependents named per group; heaviest consumer files listed where they matter.

### 1.1 Math — 9 classes, the widest surface
| Class | uses | split (app/bui/shared) |
|---|---|---|
| `com.jme.math.Vector3f` | 99 | 24/1/74 |
| `com.jme.renderer.ColorRGBA` | 67 | 4/12/51 |
| `com.jme.math.FastMath` | 55 | 9/0/46 |
| `com.jme.math.Quaternion` | 30 | 13/0/17 |
| `com.jme.math.Vector2f` | 14 | 1/0/13 |
| `com.jme.math.Matrix4f` | 5 | 5/0/0 |
| `com.jme.math.Ray` / `Plane` | 3 / 3 | — |
| `com.jme.math.Matrix3f` | 2 | 1/0/1 |

Dependents: everything. This is the bulk-rename layer that makes incremental
porting tractable.

### 1.2 Scene graph — 11 classes
| Class | uses | heaviest consumers |
|---|---|---|
| `com.jme.scene.Spatial` | 58 | sprites, BoardView, Model |
| `com.jme.scene.Controller` | 40 | `Path` (sprite motion), `ModelController` + 5 game subclasses (`WindowFader`, `Spinner`, `Bouncer`, BoardView wind, `ParticleUtil`) |
| `com.jme.scene.Node` | 33 | everywhere |
| `com.jme.scene.SharedMesh` | 8 | sprite geometry reuse (PieceTarget, PieceStatus, GenericCounterNode) |
| `com.jme.scene.TriMesh` | 7 | `ModelMesh`/`SkinMesh`, TerrainNode, WaterNode |
| `com.jme.scene.VBOInfo` | 7 | ModelMesh, TerrainNode |
| `com.jme.scene.BillboardNode` | 7 | PieceStatus, counters, effect viz |
| `com.jme.scene.Geometry` | 3 | **`BRootNode` extends it** (BUI's root is a Geometry drawn in the ortho queue) |
| `com.jme.scene.Line` | 2 | TerrainNode grid, editor TrackLayer |
| `com.jme.scene.batch.TriangleBatch`/`GeomBatch`/`SharedBatch` | 4/2/1 | ModelMesh, `BatchVisitor` (app/util) |

### 1.3 Render states — 13 classes (the fixed-function pipeline)
| Class | uses | heaviest consumers |
|---|---|---|
| `com.jme.scene.state.TextureState` | 37 | `RenderUtil` (central state factory, 587 lines), TerrainNode, BUI `BImage`, ModelMesh, TexturePool |
| `com.jme.scene.state.RenderState` (base) | 25 | RenderUtil, ParticleCache, Model |
| `com.jme.scene.state.LightState` | 20 | BoardView lighting, Model, avatar views |
| `com.jme.scene.state.MaterialState` | 16 | shared only — sprites, terrain, highlights |
| `com.jme.scene.state.AlphaState` | 7 | RenderUtil (blend presets), particles |
| `com.jme.scene.state.GLSLShaderObjectsState` | 6 | `SkinMesh` (hardware skinning), `ShaderCache`/`ShaderConfig`, WaterNode, TerrainNode, ModelCache |
| `com.jme.scene.state.ZBufferState` / `FogState` | 4 / 4 | RenderUtil, SkyNode/ParticleCache |
| `com.jme.scene.state.CullState` / `WireframeState` | 2 / 1 | RenderUtil, editor |
| `com.jme.scene.state.gdx.records.TextureStateRecord` | 3 | **backend leakage**: TexturePool, WaterNode, BackTextureRenderer poke renderer internals |
| `com.jme.scene.state.gdx.records.LineRecord` | 1 | BUI `LineBorder` |
| `com.jme.scene.state.gdx.GDXTextureState` | 1 | TexturePool |

### 1.4 Renderer — 4 classes
| Class | uses | notes |
|---|---|---|
| `com.jme.renderer.Renderer` | 90 (8/25/57) | Two distinct usage modes: (a) **BUI immediate drawing** — `BComponent.render(Renderer)` tree, `BRootNode.draw(Renderer)` in `QUEUE_ORTHO`; (b) state-factory + queue constants everywhere else (`createTextureState()`, `QUEUE_TRANSPARENT`, …) |
| `com.jme.renderer.Camera` | 17 | `CameraHandler` + subclasses (app/camera, GameCameraHandler, editor CameraDolly) |
| `com.jme.renderer.TextureRenderer` | 3 | `BackTextureRenderer`, `RenderUtil`, `RepairViz` — render-to-texture for avatar/unit portraits & effects |
| `com.jme.renderer.RenderContext` | 3 | BUI + TexturePool GL-state bookkeeping |

### 1.5 Textures & images — 4 classes
`Texture` (34), `Image` (10), `util.TextureManager` (2), `util.TextureKey` (1).
Dependents: `ImageCache`/`TexturePool`/`ModelCache` (client/util), BUI `BImage`,
TerrainNode splats, SkyNode gradients, avatar coloring (`ColorSelector` et al).

### 1.6 Binary export (Savable) — 7 classes
`OutputCapsule`/`InputCapsule`/`JMEImporter`/`JMEExporter` (29 each), `Savable` (9),
`binary.BinaryImporter` (6), `binary.BinaryExporter` (5).

Dependents — this is much bigger than models:
- **Model format**: `Model`, `ModelMesh`, `SkinMesh`, `ModelNode` + every model
  controller (app/model, 14 files) — written by `CompileModelTask`, read via
  `ModelCache` → `Model.readFromFile`.
- **Game data on disk**: `BoardFile`/`BoardData`/`BangBoard`/`Piece` hierarchy,
  `BangConfig`, `Criterion` implement Savable → 168 `.board` + 196 `.game` files.
  `BoardManager` (server) and `OfficeManager` load these at server startup.
- **Game classes serialized INSIDE model.dat**: `SpriteEmission` subclasses
  (`GunshotEmission`, `SmokePlumeEmission`, `DudShotEmission`, `MisfireEmission`,
  `FrameEmission`, `ParticleEmission` — game/client/sprite) are Savables embedded
  in compiled models.

### 1.7 System — 4 classes
`DisplaySystem` (27: 10/8/9) — global service locator (`getDisplaySystem().getRenderer()`);
BUI windows size against it; `BasicClient`/`JmeApp` create the "window" through it
(backed by `GDXDisplaySystem` since the 2017 gdx port). `JmeException` (2),
`DummyDisplaySystem` (2 — headless tools/tests), `PropertiesIO` (1).

### 1.8 Bounding — 4 classes
`BoundingBox` (19), `BoundingVolume` (6), `BoundingSphere` (5),
`OrientedBoundingBox` (1 — `TargetableActiveSprite` highlight sizing).
Dependents: sprites, ParticleCache, picking.

### 1.9 Input — 8 classes
`InputHandler` (6), `KeyInput` (4), `MouseInput` (2), `KeyBindingManager` (2),
`MouseInputListener`/`KeyInputListener` (1/1, BUI `PolledRootNode`),
`input.action.*` (2, `GodViewHandler`). The fork's `GDXKeyInput`/`GDXMouseInput`
bridge gdx events into these singletons; BUI polls them per frame;
`GameInputHandler`/camera handlers consume actions.

### 1.10 Intersection / picking — 4 classes
`TrianglePickResults` (2), `PickResults` (2), `PickData` (1), `CollisionResults` (1).
Dependents: `BoardView` mouse picking (board + sprite hit tests), BUI `BGeomView`.

### 1.11 Light — 3 classes
`PointLight` (3), `DirectionalLight` (3), `Light` (1). Dependents: BoardView
(board lights from `BangBoard` env params), editor `LightDialog`, `SkyNode`.

### 1.12 Shapes — 6 classes
`Quad` (14 — UI quads, highlights), `Box`/`Sphere`/`Dome`/`Disk`/`Pyramid` (1 each —
SkyNode dome, editor markers, effect viz).

### 1.13 Particles (`com.jmex.effects.particles`) — 6 classes
`ParticleMesh` (11), `ParticleGeometry` (4), `ParticleFactory` (4),
`ParticleController` (2), `ParticleInfluence` (1), `SimpleParticleInfluenceFactory` (1).
Dependents: `ParticleCache` (loads the 60 `particles.jme` via BinaryImporter),
`ParticlePool`, `ParticleUtil`, `GunshotEmission`/`SmokePlumeEmission`/
`MisfireEmission`/`ParticleEmission`, effect viz (`ExplosionViz`, `RepairViz`,
`WreckViz`, `HealHeroViz`), `BoardView` (dust/wind), `MobileSprite`, `PieceSprite`.

### 1.14 Terrain utils (`com.jmex.terrain.util`) — 4 classes
`AbstractHeightMap`, `MidPointHeightMap`, `FaultFractalHeightMap`,
`ParticleDepositionHeightMap` (1 each) — **editor only** (`HeightfieldBrush` /
heightfield generation dialogs).

### 1.15 Util — 6 classes
`BufferUtils` (22 — geometry construction everywhere), `ShaderAttribute` (3) /
`ShaderUniform` (2) (SkinMesh + ShaderCache), `LoggingSystem` (3), `Timer` (2,
JmeApp frame timing), `geom.Debugger` (1, BoardView debug bounds).

### 1.16 Heaviest consumer files overall (fork-import count)
```
27  app/.../jme/model/ModelMesh.java            model format
26  client/shared/.../game/client/TerrainNode.java   terrain splatting (2,182 lines)
23  client/shared/.../game/client/BoardView.java     board renderer core (2,259 lines)
21  client/shared/.../game/client/WaterNode.java     water reflections (526 lines)
20  client/shared/.../util/RenderUtil.java           central render-state factory
20  client/shared/.../game/client/sprite/GunshotEmission.java
18  client/shared/.../game/client/effect/RepairViz.java
18  app/.../jme/model/SkinMesh.java              hardware skinning (756 lines)
17  client/shared/.../game/client/SkyNode.java
16  client/shared/.../client/util/ParticleCache.java
15  app/.../jme/model/Model.java (1,191 lines); app/.../jme/JmeApp.java
```

### 1.17 Bang subsystem → fork dependency matrix
| Bang subsystem | files on fork | main fork surface |
|---|---|---|
| BUI internals (bui/) | 32 (+12 raw GL11) | Renderer draw, DisplaySystem, ortho queue, TextureState, RenderContext |
| app framework (com.threerings.jme) | 43 | everything; owns model format, camera, sprites, JmeApp |
| Board renderer (game/client root) | ~45 | states, TriMesh, TextureRenderer, particles, lights, picking |
| Sprites (game/client/sprite) | 40 | Spatial/Controller/SharedMesh/BillboardNode, particles, Savable |
| Effect viz (game/client/effect) | 14 | particles, states, BillboardNode |
| Core client glue (bang/client + util) | 26 | DisplaySystem, caches (Model/Texture/Particle/Image), BangUI |
| Editor (bang/editor) | 14 | heightmaps, wireframe, camera dolly, light dialog |
| Avatar (avatar/client) | 5 | render-to-texture views, TextureState coloring |
| Bounty (bounty/*) | 7 | BangConfig/Criterion Savable, BinaryExporter (editor) |
| Piece/board data (game/data, game/piece, util) | ~12 | Savable + math |
| Misc views (ranch, saloon, gang, chat, station…) | ~10 | BUI-adjacent, DisplaySystem, ColorRGBA |

## 2. Per-class jME3 mapping

Verdicts: **DIRECT** = rename/import swap, same semantics. **ADAPT** = exists,
API/semantics delta described. **REBUILD** = no usable equivalent; re-implement on
jME3 idioms. **DROP** = concept removed; call sites deleted or trivially absorbed.
Every target class verified present in `../jmonkeyengine` source.

### 2.1 Math & bounding
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.math.Vector3f` | DIRECT | `com.jme3.math.Vector3f` | — |
| `com.jme.math.Vector2f` | DIRECT | `com.jme3.math.Vector2f` | — |
| `com.jme.math.Quaternion` | DIRECT | `com.jme3.math.Quaternion` | minor: `fromAngleAxis` etc. all present |
| `com.jme.math.Matrix3f`/`Matrix4f` | DIRECT | `com.jme3.math.Matrix3f`/`Matrix4f` | — |
| `com.jme.math.FastMath` | DIRECT | `com.jme3.math.FastMath` | — |
| `com.jme.math.Ray` | DIRECT | `com.jme3.math.Ray` | picking entry point changes (§2.8) |
| `com.jme.math.Plane` | DIRECT | `com.jme3.math.Plane` | — |
| `com.jme.renderer.ColorRGBA` | DIRECT | `com.jme3.math.ColorRGBA` | **package move** renderer→math; field-compatible |
| `com.jme.bounding.BoundingBox` | DIRECT | `com.jme3.bounding.BoundingBox` | merge/contains API equivalent |
| `com.jme.bounding.BoundingSphere` | DIRECT | `com.jme3.bounding.BoundingSphere` | — |
| `com.jme.bounding.BoundingVolume` | DIRECT | `com.jme3.bounding.BoundingVolume` | — |
| `com.jme.bounding.OrientedBoundingBox` | REBUILD | — | jME3's `OrientedBoundingBox.java` is **commented out** (dead stub). One consumer (`TargetableActiveSprite`); replace with `BoundingBox` or local OBB math |

### 2.2 Scene graph
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.scene.Node` | DIRECT | `com.jme3.scene.Node` | attach/detach identical |
| `com.jme.scene.Spatial` | ADAPT | `com.jme3.scene.Spatial` | `setRenderState`→`Material` (per-Geometry); `setRenderQueueMode(QUEUE_*)`→`setQueueBucket(Bucket.*)`; `setCullMode`→`setCullHint`; `updateRenderState()` gone; `updateGeometricState(time,initiator)`→`updateLogicalState(tpf)`+`updateGeometricState()`; `draw(Renderer)`/`onDraw` hooks gone (see BUI §3.3); `lockMeshes` → `setStatic`-style batching gone (use `BatchNode`/leave dynamic) |
| `com.jme.scene.Controller` | ADAPT | `com.jme3.scene.control.AbstractControl` | `update(float)`→`controlUpdate(float)`; attach via `spatial.addControl`; both Savable, so serialized controllers in model.dat port cleanly; active/speed/repeat-type flags must be re-implemented in a small base class (jME3 has no speed/repeat on Control) |
| `com.jme.scene.TriMesh` | ADAPT | `com.jme3.scene.Mesh` + `com.jme3.scene.Geometry` | the **TriMesh→Mesh/Geometry split**: buffers move to `Mesh` `VertexBuffer`s; the scene-graph node is `Geometry`. Mechanical but touches every mesh-building site (`BufferUtils` patterns survive) |
| `com.jme.scene.SharedMesh` | ADAPT | `com.jme3.scene.Geometry` (shared `Mesh`) | jME3 Geometries share one Mesh natively; SharedMesh ceases to exist as a class |
| `com.jme.scene.Geometry` | ADAPT | `com.jme3.scene.Geometry` | **semantic change**: fork Geometry = abstract batched geometry; jME3 Geometry = Mesh holder. BUI's `BRootNode extends Geometry` must become a Node + custom Geometries (§3.3) |
| `com.jme.scene.BillboardNode` | ADAPT | `com.jme3.scene.control.BillboardControl` | node→control; alignment enums map (SCREEN_ALIGNED→Screen, AXIAL→AxialY) |
| `com.jme.scene.Line` | ADAPT | `Mesh` with `Mode.Lines` (or `com.jme3.scene.shape.Line`) | fork Line is multi-segment geometry; build a Lines-mode Mesh |
| `com.jme.scene.VBOInfo` | DROP | — | jME3 manages VBOs automatically |
| `com.jme.scene.batch.TriangleBatch` | DROP | — | batches removed in jME3; rewrite the few accessors (`ModelMesh`, `BatchVisitor`) against `Mesh` buffers during the model port |
| `com.jme.scene.batch.GeomBatch`/`SharedBatch` | DROP | — | same |

### 2.3 Shapes
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.scene.shape.Quad` | ADAPT | `com.jme3.scene.shape.Quad` | becomes a `Mesh` (wrap in Geometry); fork Quad was a node |
| `com.jme.scene.shape.Box` | ADAPT | `com.jme3.scene.shape.Box` | same node→mesh pattern |
| `com.jme.scene.shape.Sphere` | ADAPT | `com.jme3.scene.shape.Sphere` | same |
| `com.jme.scene.shape.Dome` | ADAPT | `com.jme3.scene.shape.Dome` | same (SkyNode) |
| `com.jme.scene.shape.Disk` | REBUILD | — | no jME3 Disk; ~50-line custom Mesh (1 consumer) |
| `com.jme.scene.shape.Pyramid` | REBUILD | — | no jME3 Pyramid; ~50-line custom Mesh (1 consumer) |

### 2.4 Render states → Material system
jME3 has no stateful fixed-function pipeline: a `Geometry` gets a
`com.jme3.material.Material` (a `.j3md` MatDef + params); blend/depth/cull/wire
live on `material.getAdditionalRenderState()` (`com.jme3.material.RenderState`);
lights attach to Spatials. **Every `setRenderState` call site changes shape.**

| Fork class | Verdict | jME3 mechanism | Notes |
|---|---|---|---|
| `state.RenderState` (base type) | REBUILD | `Material` (+ `material.RenderState`) | the "apply a state object to a spatial" idiom dies; `RenderUtil`'s shared-state factories become a shared-Material library |
| `state.TextureState` | REBUILD | `Material` texture params (`setTexture("ColorMap"/"DiffuseMap", tex)`) | multi-unit combine setups (terrain splats, avatar tinting) need custom MatDefs |
| `state.LightState` | REBUILD | `Spatial.addLight` + `Lighting.j3md` | fork carries OOO mods (localViewer, separateSpecular) — both are standard in jME3's lighting shader |
| `state.MaterialState` | REBUILD | `Material` params (Diffuse/Ambient/Specular/Shininess) + `Boolean UseVertexColor` | fork carries OOO colorMaterial mod → vertex-color material param |
| `state.AlphaState` | ADAPT | `material.RenderState` `setBlendMode` + alpha-discard (`AlphaDiscardThreshold` param) | preset blends in RenderUtil map 1:1 to `BlendMode.Alpha/Additive/...` |
| `state.ZBufferState` | ADAPT | `material.RenderState` `setDepthTest/setDepthWrite` | — |
| `state.CullState` | ADAPT | `material.RenderState` `setFaceCullMode` | — |
| `state.WireframeState` | ADAPT | `material.RenderState` `setWireframe` | editor only |
| `state.FogState` | REBUILD | fog param in custom MatDefs or `com.jme3.post.filters.FogFilter` (jme3-effects) | board fog is per-board ambience; FogFilter (post) is the cheapest visual match |
| `state.GLSLShaderObjectsState` | REBUILD | `Material`/MatDef with `.vert`/`.frag` | SkinMesh skinning, water, terrain shaders become MatDefs; `ShaderCache`/`ShaderConfig` (app/util) are subsumed by jME3's asset/shader-define system |
| `util.ShaderAttribute`/`ShaderUniform` | REBUILD | vertex buffers / `MatParam` | skinning attributes → `VertexBuffer.Type.BoneWeight/BoneIndex` or custom buffers |
| `state.gdx.records.TextureStateRecord`, `records.LineRecord`, `gdx.GDXTextureState` | DROP | — | backend internals (gdx renderer); the texture-pool trickery they enable is redone with `Texture2D`/`Image` APIs |

### 2.5 Renderer / camera / display
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.renderer.Renderer` | ADAPT+REBUILD | `com.jme3.renderer.RenderManager` (+ `Renderer` for low-level) | constants (`QUEUE_*`)→`RenderQueue.Bucket`; state factories vanish (Materials); **BUI's draw(Renderer) immediate path is REBUILD** (§3.3) |
| `com.jme.renderer.Camera` | ADAPT | `com.jme3.renderer.Camera` | concrete class, no `update()` needed; `setFrustum*`/`setLocation` same; direction set via `lookAtDirection`/axes instead of separate vectors; `CameraHandler` ports with modest edits |
| `com.jme.renderer.TextureRenderer` | REBUILD | `FrameBuffer` + offscreen `ViewPort` (or `RenderManager.renderViewPort`) | render-to-texture is viewport-based; `BackTextureRenderer` (back-buffer copy trick) → plain offscreen FBO; consumers: avatar/unit portraits, RepairViz |
| `com.jme.renderer.RenderContext` | DROP | — | GL-state bookkeeping is renderer-internal in jME3 |
| `com.jme.system.DisplaySystem` | REBUILD | none — architectural | jME3 has no global locator; the app owns `AssetManager`/`RenderManager`/`Camera`/`ViewPort`. Replace with accessors on `BasicContext`/`JmeContext` (already threaded through most call sites) |
| `com.jme.system.DummyDisplaySystem` | DROP | `JmeContext.Type.Headless` | tools/tests run headless |
| `com.jme.system.JmeException` | ADAPT | `RuntimeException` | trivial |
| `com.jme.system.PropertiesIO` | ADAPT | `com.jme3.system.AppSettings` | trivial |

### 2.6 Textures, images, util
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.image.Texture` | ADAPT | `com.jme3.texture.Texture`/`Texture2D` | enums move (`WM_*`→`WrapMode`, `MM_/FM_*`→`MinFilter`/`MagFilter`); `setApply` combine modes have **no equivalent** — combine users (terrain, tinting) go through custom MatDefs |
| `com.jme.image.Image` | ADAPT | `com.jme3.texture.Image` | format enum differs (`Format.RGBA8`); data is `ByteBuffer` list |
| `com.jme.util.TextureManager` | ADAPT | `com.jme3.asset.AssetManager` (`loadTexture`) | Bang mostly loads via its own `ImageCache`/`TexturePool` anyway; those caches re-target AssetManager or build `Image`s directly |
| `com.jme.util.TextureKey` | ADAPT | `com.jme3.asset.TextureKey` | ParticleCache uses it to redirect texture paths during `.jme` import — same hook exists on jME3 asset keys, but the particle loader is rebuilt anyway |
| `com.jme.util.geom.BufferUtils` | DIRECT | `com.jme3.util.BufferUtils` | same statics |
| `com.jme.util.Timer` | ADAPT | `com.jme3.system.Timer`/`NanoTimer` | no global `getTimer()`; app supplies it |
| `com.jme.util.LoggingSystem` | DROP | `java.util.logging` | — |
| `com.jme.util.geom.Debugger` | ADAPT | `com.jme3.scene.debug.*` (WireBox et al) | debug-bounds drawing is attach-a-wire-shape instead of renderer call |

### 2.7 Binary export (Savable)
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `com.jme.util.export.Savable` | ADAPT | `com.jme3.export.Savable` | same two-method contract |
| `com.jme.util.export.InputCapsule`/`OutputCapsule` | ADAPT | `com.jme3.export.InputCapsule`/`OutputCapsule` | same named-field+default pattern; `readSavableArray`/`writeSavableArrayList` all present |
| `com.jme.util.export.JMEImporter`/`JMEExporter` | ADAPT | `com.jme3.export.JmeImporter`/`JmeExporter` | case rename |
| `binary.BinaryImporter`/`BinaryExporter` | ADAPT | `com.jme3.export.binary.BinaryImporter`/`BinaryExporter` | API ports cleanly; **the on-disk format and embedded class names are incompatible** — every existing `.jme`/`.board`/`.game`/`model.dat` is unreadable by jME3 (§5) |

### 2.8 Intersection / picking
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `intersection.PickResults`/`TrianglePickResults` | ADAPT | `com.jme3.collision.CollisionResults` + `spatial.collideWith(ray, results)` | jME3 picking is always triangle-accurate on meshes; `findPick(ray,results)`→`collideWith`; distance sorting built in |
| `intersection.PickData` | ADAPT | `com.jme3.collision.CollisionResult` | `getTargetMesh`→`getGeometry`, contact point/triangle available |
| `intersection.CollisionResults` | ADAPT | `com.jme3.collision.CollisionResults` | BUI BGeomView only |

### 2.9 Lights
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `light.DirectionalLight` | ADAPT | `com.jme3.light.DirectionalLight` | no enable flag/attach-to-LightState; `spatial.addLight`; diffuse/ambient/specular collapse to one color (ambient via separate `AmbientLight`) |
| `light.PointLight` | ADAPT | `com.jme3.light.PointLight` | attenuation → radius model |
| `light.Light` | ADAPT | `com.jme3.light.Light` | — |

### 2.10 Input
| Fork class | Verdict | jME3 target | Delta |
|---|---|---|---|
| `input.InputHandler` | REBUILD | `com.jme3.input.InputManager` (mappings) or direct gdx→BUI dispatch | fork model = per-frame polled action lists; jME3 = listener mappings. Note Bang's real input source is **libGDX** today (fork `GDXKeyInput`/`GDXMouseInput`); during transition keep gdx→BUI dispatch, end-state jME3 `RawInputListener`→BUI |
| `input.KeyInput` | ADAPT | `com.jme3.input.KeyInput` | KEY_* constants exist with same names (values differ — don't persist raw codes) |
| `input.MouseInput` | ADAPT | `com.jme3.input.MouseInput` | polled API (`getXAbsolute`…) → event deltas |
| `input.KeyBindingManager` | REBUILD | `InputManager.addMapping` + `KeyTrigger` | small (2 consumers) |
| `input.KeyInputListener`/`MouseInputListener` | REBUILD | `com.jme3.input.RawInputListener` | BUI `PolledRootNode` is the consumer; becomes one RawInputListener |
| `input.action.*` (`InputActionEvent`…) | REBUILD | `AnalogListener`/`ActionListener` | only `GodViewHandler`; rewrite alongside camera handlers |

### 2.11 Particles (`com.jmex.effects.particles`)
All REBUILD → `com.jme3.effect.ParticleEmitter` + `ParticleInfluencer`
(jme3-core `com.jme3.effect.*`, verified). The two systems differ structurally:
fork = `ParticleGeometry` subclassing TriMesh with a `ParticleController` and
arbitrary `ParticleInfluence` list (wind/drag/gravity/swarm via
`SimpleParticleInfluenceFactory`); jME3 = `ParticleEmitter` Geometry with one
`ParticleInfluencer`, built-in gravity/velocity-variation, `Particle.j3md`
point/triangle modes.

| Fork class | jME3 analogue |
|---|---|
| `ParticleMesh`/`ParticleGeometry` | `ParticleEmitter` (+ `ParticleMesh.Type.Triangle`) |
| `ParticleFactory` | `new ParticleEmitter(...)` |
| `ParticleController` | internal to emitter (`setParticlesPerSec`, control) |
| `ParticleInfluence`/`SimpleParticleInfluenceFactory` | `ParticleInfluencer` impls; wind/drag need custom influencers |

The 60 binary `particles.jme` definitions must be converted offline (§5.3);
emission classes that hand-build geometry (`GunshotEmission` streaks,
`SmokePlumeEmission` plumes) are custom Mesh code to port by hand.

### 2.12 Terrain utils (`com.jmex.terrain.util`)
| Fork class | Verdict | jME3 target (jme3-terrain) |
|---|---|---|
| `AbstractHeightMap` | ADAPT | `com.jme3.terrain.heightmap.AbstractHeightMap` |
| `MidPointHeightMap` | ADAPT | `...heightmap.MidpointDisplacementHeightMap` (different ctor params) |
| `FaultFractalHeightMap` | ADAPT | `...heightmap.FaultHeightMap` (closest analogue; tuning differs) |
| `ParticleDepositionHeightMap` | ADAPT | `...heightmap.ParticleDepositionHeightMap` |

Editor-only, low risk. (Bang's actual in-game terrain mesh is its own code in
`TerrainNode`, not jME terrain — it stays custom, only its materials change.)

### 2.13 Fork subsystems with ZERO Bang usage (die with the fork)
`com.jme.renderer.pass` (9 classes), `com.jme.scene.shadow` (4), `com.jme.scene.lod`
(7, ClodMesh), `com.jme.math.spring` (4), `com.jmex.effects.glsl` (3, bloom/sketch),
`com.jmex.effects.transients` (4), `com.jme.input.controls` (4+2), `com.jme.input.util`
(7), `com.jme.input.action` (27, all but one unused), `com.jme.util.geom.nvtristrip`
(12 — not even the model compiler uses it), `com.jmex.awt` (5), `com.jme.input.keyboard`
(2), plus the whole gdx backend (`renderer.gdx` 3, `system.gdx` 2, `input.gdx` 2,
`scene.state.gdx` 17+21 records, `util.lwjgl` 1) and `util.export.binary.modules`
(18 — needed only if we keep a legacy-format reader, §5.2). No work beyond deletion.

### 2.14 Three Rings fork customizations (port as custom code, not mapping)
Verified divergences from stock jME (explicit `@author Three Rings` markers plus
the structurally obvious 2017 additions):

| Where | What | Disposition on jME3 |
|---|---|---|
| `jme/.../renderer/RenderQueue.java:296` | "better sorting alg" for the transparent queue | jME3 sorts transparent back-to-front via `TransparentComparator`; **verify particle/billboard layering visually**; custom `GeometryComparator` is the escape hatch if fidelity differs |
| `jme/.../scene/state/LightState.java:56` | local viewer + separate specular | standard in jME3 `Lighting.j3md` — drop |
| `jme/.../scene/state/MaterialState.java:51` | contributed colorMaterial | `UseVertexColor`/material params — drop |
| whole gdx backend (≈46 classes) | 2017 LWJGL-display→libGDX rehost | superseded by jME3's own context system — drop |
| `app/` module (56 classes, `com.threerings.jme.*`) | entire OOO framework: JmeApp loop, camera handlers/paths, sprite/path system, **model format + compiler + hardware skinning**, ShaderCache, ImageCache | this is not "mapped" — it is the primary porting workload, rebuilt on jME3 (§3) |
| `bui/` module (88 classes) | OOO UI toolkit in `com.jmex.bui` | API kept, render internals rebuilt (§3.3) |

A line-by-line diff against upstream jME 1.0 CVS was not attempted (no upstream
checkout vendored); the fork-wide `$Id$` headers are unexpanded, so provenance
beyond the markers above is unknowable cheaply. Mitigation: behavior-level visual
regression rather than source archaeology.

## 3. The REBUILD workload (project-owned code, not class mapping)

The per-class table (§2) covers the engine fork. The bulk of the actual labour,
though, is in the two project-owned modules whose public surface survives but whose
internals are fixed-function and must be rebuilt on jME3 idioms.

### 3.1 `app/` — the OOO framework (56 classes, 43 fork-importing)
`com.threerings.jme.*` is Three Rings' own layer on the fork; it has no jME3
counterpart and is reimplemented, not mapped:

- **`JmeApp` main loop** → a `com.jme3.app.SimpleApplication` (or a custom
  `Application` subclass when the gdx host is retired). `update`/render driving,
  `Timer`, resize handling (engine-notes §threading: the 1×1-init reinit dance
  goes away — jME3 owns the context and sizes it correctly).
- **Camera handlers / `CameraHandler` + `Path`** → `com.jme3.renderer.Camera`
  (concrete) + app-side controllers; smooth-motion `Path`/`PathController` becomes
  jME3 `MotionPath`/`CinematicEvent` or a small custom `Control`.
- **Sprite + model framework** (`Sprite`, `Model`, `ModelMesh`, `SkinMesh`,
  `ModelNode`, controllers) → the single largest rebuild; see §3.2.
- **`ShaderCache`/`ShaderConfig`, `ImageCache`** → subsumed by jME3's
  `AssetManager`, MatDef define-system, and `Texture` caching.
- **`BatchVisitor`, `SpatialVisitor`** → walk `Geometry`/`Mesh` instead of batches.

### 3.2 The model format + compiler (highest single risk)
`CompileModelTask` (app/.../jme/tools/CompileModelTask.java — **project code**, not
nenya-tools) parses `model.properties` + sibling XML/MD/texture sources and writes a
fork-`BinaryExporter` `model.dat`. 310 models. `SkinMesh` does GLSL hardware skinning
via `GLSLShaderObjectsState` + `ShaderAttribute`. Rebuild path:

1. Keep the XML/`model.properties` parse (source of truth is unchanged).
2. Emit jME3 geometry (`Mesh` + `VertexBuffer`s) instead of fork `TriMesh`/batches;
   serialize to `.j3o` via jME3 `BinaryExporter` *or* build at load time from XML.
3. Re-express skinning as jME3 `com.jme3.anim` (`SkinningControl`/`Armature`) or a
   custom skinning MatDef; `SpriteEmission` Savables embedded in `model.dat`
   (Gunshot/SmokePlume/DudShot/Misfire/Frame/Particle — game/client/sprite) must be
   re-emitted in the new format.
   **Consequence: every `model.dat` is regenerated from source by the build (§5), so
   there is no binary-migration step — but the compiler itself is a rewrite.**

### 3.3 BUI render layer (88 classes, 32 fork-importing; 12 import `org.lwjgl`, 10 of those call `org.lwjgl.opengl.GL*` directly)
BUI's public component API (BWindow, BButton, layouts, events) is **kept**. What
changes is how it reaches the screen:

- `BRootNode extends com.jme.scene.Geometry` and overrides `draw(Renderer)` to walk
  the component tree doing immediate-mode draws in `QUEUE_ORTHO`. jME3 has **no
  `draw(Renderer)` hook and no immediate mode**. Rebuild `BRootNode` as a `Node` in
  the `Bucket.Gui` ortho viewport whose render is driven by a custom `SceneProcessor`
  or per-component `Geometry`+`Material` (quad + textured material + BMFont text).
- The 10 files calling `org.lwjgl.opengl.GL11`/`GL12` directly (scissor clipping,
  texture binds, blend setup, `BImage` upload, `LineBorder`) must move to jME3
  Material/RenderState + `Texture2D` APIs. Scissor-rect clipping (BUI's
  `intersectScissorBox`) maps to jME3 `GuiViewPort` clip or a stencil/`ClipState`-style
  custom processor — **no direct jME3 equivalent; this is the fiddliest BUI work.**
- Input: `PolledRootNode` consumes fork `KeyInput`/`MouseInput` singletons; becomes a
  single `com.jme3.input.RawInputListener` feeding BUI's event model (§2.10).
- Alternative (flagged in UPGRADE_PLAN Phase 5 step 3): replace BUI with Lemur —
  bigger diff, better long-term support, but discards a working, game-tuned toolkit.

## 4. Risk register (ranked REBUILD areas)

Effort in agent-days, ordered by risk = effort × fidelity-uncertainty.

| # | Area | Effort | Why it is risky |
|---|---|---|---|
| 1 | **Model pipeline + compiler + hardware skinning** (§3.2) | 15–25 d | Custom binary format, GLSL skinning, embedded emission Savables; blocks everything visual; no reference port exists. jME3 anim system is structurally different from the fork's `BoneTransform` controllers. |
| 2 | **BUI render layer + scissor clipping** (§3.3) | 10–18 d | Immediate-mode `draw(Renderer)` has no jME3 hook; scissor clipping has no clean equivalent; 10 raw-GL files; whole UI must keep pixel fidelity or every screen regresses. |
| 3 | **Board renderer: terrain splat + water + sky** (TerrainNode 2,182 ln, WaterNode 526 ln, SkyNode) | 12–20 d | Multi-texture splat combine modes (`Texture.setApply`) have **no jME3 equivalent** → custom MatDef per splat layer; WaterNode pokes renderer internals + needs GL-thread init + reflection FBO; highest fidelity-risk per UPGRADE_PLAN itself. |
| 4 | **Render-state → Material conversion across all call sites** (§2.4, RenderUtil 587 ln central) | 8–14 d | Every `setRenderState` site changes shape; `RenderUtil`'s shared-state factory becomes a shared-Material library; touches ~45 board-renderer + all sprite/effect files. Mechanical but vast and easy to regress blend/depth ordering. |
| 5 | **Particle systems** (§2.11; 60 `.jme` + hand-built emission geometry) | 8–12 d | fork `ParticleGeometry`/influence model ≠ jME3 `ParticleEmitter`/`ParticleInfluencer`; 60 binary defs need an offline converter; Gunshot/SmokePlume build geometry by hand. Custom influencers (wind/drag) have no built-in jME3 analogue. |
| 6 | DisplaySystem service-locator removal (§2.5) | 4–7 d | 27 files call `getDisplaySystem()...`; architectural — thread `AssetManager`/`RenderManager`/`Camera` through call sites. Mechanical, wide, low fidelity risk. |
| 7 | Input model (§2.10) | 3–5 d | Polled → listener; small consumer count but couples to BUI rebuild and the gdx-retirement seam. |
| 8 | Camera handlers / paths / `JmeApp` loop (§3.1) | 4–6 d | Concrete jME3 Camera + app model; modest, mostly straightforward. |
| 9 | Editor (heightmaps, wireframe, light dialog) | 3–5 d | jme3-terrain heightmaps exist (§2.12); Swing-embedded GL canvas (`LwjglCanvas`) → jME3 `AWTPanels`/`JmeCanvasContext`; editor-only, deferrable. |

**Aggregate REBUILD effort ≈ 70–110 agent-days (3.5–5.5 agent-months)**, before
integration, per-town visual regression, and bug-fix tail. UPGRADE_PLAN's "2–3
months" is optimistic for the rebuild bottom-up but plausible *with heavy parallelism
and human visual review*; items 1–3 are the schedule-defining critical path and are
mutually fairly independent, so they parallelize well.

## 5. Asset inventory: regenerate vs. convert

The decisive fact: **the fork `BinaryImporter`/`BinaryExporter` on-disk format and
its embedded class names are incompatible with jME3's** — no existing binary asset is
readable by jME3. Disposition splits by whether a source-of-truth exists.

### 5.1 Regenerate from source (no conversion needed)
| Asset | Count | Source of truth | Action |
|---|---|---|---|
| `model.dat` | 310 (`model.properties`) | XML / `model.properties` | rebuilt by the (rewritten) compiler each `assets:deploy` — §3.2 |
| Avatar bundles, compiled configs | many | XML under `rsrc/` | regenerated by `assets:processResources` (already so; nenya-side, format-stable) |

These are **not migrated** — the pipeline already rebuilds them from XML every build
(UPGRADE_PLAN risk row "Compiled binary configs… regen, don't reuse" applies here).

### 5.2 Convert offline (binary is the source of truth — no XML to regenerate from)
| Asset | Count | Format | Action |
|---|---|---|---|
| `.board` (game boards) | 168 (165 in `data/boards/<town>/<n>/` under `server_root`, + 3 menu `town.board` backdrops in `assets/rsrc/menu/<town>/`) | fork `BinaryExporter` Savable graph (`BoardFile`→`BangBoard`+`Piece[]`) | one-time converter: load with the **legacy fork reader retained as a tool**, re-save via jME3 (or via Narya streaming — see §6). The server reads `data/boards` at startup (`BoardManager.mapBoards(new File(ServerConfig.serverRoot, "data/boards"))`, BoardManager.java:44); the board *editor* writes them. |
| bounty `.game` | 196 (`assets/rsrc/bounties/…`) | fork `BinaryExporter` (`BangConfig`+`Criterion` Savables) | same one-time converter. |
| `particles.jme` | 60 (`assets/rsrc/effects/…`) | fork `BinaryExporter` `ParticleGeometry` | converter must **re-author** as jME3 `ParticleEmitter` params (structural mismatch §2.11) — not a mechanical re-save; likely a parameter-extraction script + hand tuning. |

**Retention plan:** keep a *read-only* slice of the fork's export subsystem
(`util.export` + `util.export.binary` + `binary.modules`, ~25 classes) compiled into
a **one-shot conversion tool**, not the runtime. Run it once to migrate the 424
binary assets, then delete it. This is the only fork code that survives past the port,
and only transiently.

### 5.3 Total binary-asset migration: 424 files
168 `.board` + 196 `.game` + 60 `particles.jme`. Boards/bounties are
mechanical re-saves; particles are re-authoring (folded into risk #5).

## 6. Corrections, migration order, and the LWJGL2→LWJGL3 cutover seam

### 6.1 Corrections to prior docs (verified this inventory)
- **`.board`/`.game` are fork `BinaryExporter` Savables, NOT Narya streaming.**
  `docs/engine-notes.md` §Boards calls a board "a serialized `BoardData` written with
  Narya's streaming system." Verified false for the on-disk file:
  `BoardFile.loadFrom` calls `com.jme.util.export.binary.BinaryImporter.getInstance().load(...)`
  and `BoardFile implements com.jme.util.export.Savable`
  (client/shared/.../game/util/BoardFile.java:13-19,71,80,89,98). `BoardData` *is*
  Narya-`Streamable` for the **wire** (server→client), but the **file** is jME binary.
  This makes the 168+196 files a jME-format migration concern, exactly the kind
  UPGRADE_PLAN Phase 5 must own — they do **not** "rebuild from XML."
- **Game boards live in `data/boards/<town>/<n>/` under `server_root`** (165 files;
  `BoardManager.java:44`), with only the 3 menu `town.board` backdrops under
  `assets/rsrc/menu/`. There is no `assets/rsrc/boards/` directory. `engine-notes.md`
  is correct; an earlier draft of this map wrongly "corrected" it to `rsrc/` — reverted.
- **`CompileModelTask` is project code** (app/.../jme/tools/CompileModelTask.java),
  not nenya-tools as UPGRADE_PLAN Phase 5 step 2 implies ("the XML→`.jme` binary
  compiler (`CompileModelTask`)" — it *is* the fork-binary compiler, and **we own its
  source**, which de-risks the rewrite).
- jME3 `BinaryImporter`/`BinaryExporter` live under **`jme3-core/src/plugins/`** (not
  `main`); `FogFilter` is in **`jme3-effects`**; heightmaps in **`jme3-terrain`**.
  Dependency implication: the port pulls `jme3-core`, `jme3-effects`, `jme3-terrain`,
  `jme3-lwjgl3`, and `jme3-plugins`/`jme3-blender` (for asset IO) — not core alone.
- jME3 `OrientedBoundingBox` exists but is a **dead stub** (1,520 lines, 1,483 of them
  commented out) — REBUILD verdict stands.

### 6.2 Recommended migration order
Bottom-up, so each layer compiles against a working layer below it:

1. **Math/bounding bulk-rename** (DIRECT/ADAPT, §2.1) — pure import swaps; makes the
   tree compile against `com.jme3.math.*`. Do first, mechanically, one commit.
2. **Savable/export rename** (§2.7) for the in-memory classes (Model, Piece, Config);
   plus stand up the §5.2 conversion tool.
3. **Model compiler + format + skinning** (risk #1) — unblocks all geometry.
4. **Render-state → Material** layer (`RenderUtil`, risk #4) — the shared-Material
   library everything else draws from.
5. **app framework** (JmeApp loop, camera, sprites — §3.1) on a `SimpleApplication`.
6. **Board renderer** (terrain/water/sky, risk #3) + **particles** (risk #5).
7. **BUI render layer** (risk #2) + input (risk #7).
8. **Editor** (risk #9), then per-town visual regression.

### 6.3 The LWJGL2 → LWJGL3 cutover seam (single hard switch)
**The two engines cannot share a window or GL context.** Today (engine-notes §rendering
stack): libGDX 1.5.4 (`LwjglApplication`) owns the LWJGL **2.9** window + GL context +
main loop + input + the OpenAL device; the fork renders into that context; BUI renders
inside the fork; the vendored `com.threerings.openal` shares gdx's AL device. jME3
ships its own context on **LWJGL 3** (`jme3-lwjgl3`, `LwjglWindow`/GLFW).

Therefore the cutover is **one atomic switch**, and the seam must sit at the
**application-host boundary — `BangDesktop`/`BangApp` (gdx `ApplicationListener`) and
`EditorDesktop`**, replacing them with a jME3 `Application`/`JmeContext` that creates
the LWJGL3 window directly. There is **no incremental dual-context phase**: you cannot
run jME3 geometry inside the LWJGL2/gdx context, nor the fork inside LWJGL3. Practical
sequencing:

- Steps 1–4 above are **engine-agnostic** (code/format changes) and can land while the
  app still boots on the old fork+gdx+LWJGL2 host — they keep the legacy build alive.
- The host switch happens **with step 5** (app framework on `SimpleApplication`): at
  that commit the window/context/loop/input flip from gdx+LWJGL2 to jME3+LWJGL3 in one
  move. UPGRADE_PLAN Phase 5 step 6 ("switch windowing to LWJGL3… comes free with jME3")
  is right that it is free, but **wrong that it is a late, optional step** — it is the
  unavoidable hinge of step 5 and everything after it runs on LWJGL3.
- **Sound moves with the window.** Retiring gdx removes the AL device owner; the
  vendored `com.threerings.openal` must bind to a device the new host opens (jME3's
  audio context, also LWJGL3-`al`), or be replaced by jME3 `AudioRenderer`. This is a
  Phase-5 dependency UPGRADE_PLAN's outline does not mention.
- **libGDX can then be removed entirely** (it only does window/input/AL, all covered by
  jME3) — the 25 gdx-using files collapse to the host shim. The 29 client/app files
  using `org.lwjgl` directly (LWJGL2 API) must move to LWJGL3 signatures
  (`ByteBuffer`/`MemoryStack` style) at the same seam.

### 6.4 What this invalidates in UPGRADE_PLAN Phase 5
- Step 2 mis-attributes `CompileModelTask` to nenya-tools and calls the output `.jme`;
  it is project-owned and emits `model.dat` (good news: we own the rewrite).
- Step 6 frames LWJGL3 as a free, late, optional step. It is unavoidable and is the
  hinge of step 4/5 (app host), not a tail item; and it drags **OpenAL/sound** with it.
- The "regen, don't reuse" risk-register line does **not** cover `.board`/`.game`:
  those have no XML source and need a one-time fork→jME3/Narya **conversion tool**
  (§5.2), an asset-migration task absent from the current plan.
