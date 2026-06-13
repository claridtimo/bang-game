# Bang model pipeline → jMonkeyEngine 3 (Phase 5, step 2)

Format analysis of Bang! Howdy's model pipeline and a prototype converter to jME 3.9.0
`.j3o`. Companion code lives in `tools/j3o-converter/`. Read `docs/engine-notes.md`
("The model pipeline") first for pipeline wiring; this document covers the *formats*.

## 1. The source format

Each model is a directory under `assets/rsrc/**` containing:

```
model.properties     # metadata: name, scale, animation list, controllers, texture options
model.mxml           # geometry + node hierarchy exported from 3ds Max (XML)
<anim>.mxml          # one per animation listed in model.properties (units etc. only)
*.png                # textures, referenced by file name from the mxml
```

310 such directories exist: **224 props, 37 units, 31 bonuses, 18 extras**
(`find rsrc -name model.properties`). 36 are animated (21 units, 9 extras, 3 bonuses,
3 props); 61 declare procedural/emission controllers; 4 declare variants.

### 1.1 `model.properties`

Parsed by `com.threerings.jme.tools.CompileModel` (vendored in `app/`, invoked by
nenya-tools' `CompileModelTask` from `assets/build.gradle compileModels`). Keys observed
across the corpus:

| Key | Meaning |
|---|---|
| `name` | model name (root node name) |
| `scale` | uniform local scale applied to the root (`Model.setLocalScale`) |
| `animations` | list of animation names; each `<name>.mxml` is compiled in |
| `<anim>.repeat_type` | `clamp` (default) / `wrap` / `cycle` per animation |
| `sequences`, `<seq>.animations` | client-side chaining of animations (used by sprites, not the compiler) |
| `idle` | which animation sprites play at rest (runtime-only) |
| `controllers` | list of controller config names |
| `<ctrl>.class` | controller class (see §3); `<ctrl>.node` optional target node name |
| `<ctrl>.*` | controller-specific config (e.g. emission `frames`, `shot_frame`) |
| `variants` | named variant configurations (`Model.createPrototype(variant)` re-filters all mesh properties by `<variant>.` prefix) |
| `<mesh>.bound` | `sphere` to use a bounding sphere instead of box |
| `<texture>` | remaps a texture file name from the mxml to one *or several* file names — multiple values mean "pick one at random per instance" (e.g. cow hide variations) |
| `<texture>.sphere_map/filter/mipmap/compress/emissive/additive/alpha_threshold/translucent` | per-texture render options baked into each `ModelMesh` |

Properties are *filtered by prefix* per node name (`PropertiesUtil.getFilteredProperties`),
so any key can be scoped to a specific mesh/node. The **entire Properties table is
serialized into `model.dat`** (`propNames`/`propValues`), so runtime code (sprites) can read
`idle`, `sequences`, etc. from the loaded model.

### 1.2 `model.mxml` (geometry)

Parsed by `com.threerings.jme.tools.xml.ModelParser` (commons-digester). Schema:

```xml
<model>
  <node     name=".." translation="x, y, z" rotation="x, y, z, w" scale="x, y, z"/>
  <triMesh  name=".." parent=".." translation=".." rotation=".." scale=".."
            offsetTranslation=".." offsetRotation=".." offsetScale=".."
            solid="true|false" transparent="true|false" texture="file.png">
    <vertex location="x, y, z" normal="x, y, z" tcoords="u, v"/>
    ...
  </triMesh>
  <skinMesh ...same attributes...>
    <vertex location=".." normal=".." tcoords="..">
      <boneWeight bone="nodeName" weight="0.75"/>
      ...
    </vertex>
  </skinMesh>
</model>
```

- `<node>` = transform/bone node (`ModelDef.NodeDef`); `parent` attributes build the
  hierarchy by name; parentless spatials attach to the model root.
- `<triMesh>` = rigid geometry (`ModelDef.TriMeshDef`); vertices are deduplicated into an
  indexed triangle list as they are added.
- `<skinMesh>` = skinned geometry (`ModelDef.SkinMeshDef`); each vertex carries named
  bone weights (bones are `<node>`s in the same file).

### 1.3 `<anim>.mxml` (animations)

Parsed by `AnimationParser` into `AnimationDef`: a `frameRate` plus a flat list of
`<frame>` elements, each containing `<transform name=".." translation=".." rotation=".."
scale=".."/>` for every node that moves. i.e. **fully sampled per-frame TRS keyframes on
named nodes** — no curves, no interpolation data, no per-vertex morph targets in the
source. Skinning deformation comes from skin meshes' bone weights referencing those nodes.

### 1.4 What the compiler does (`CompileModel.compile`)

1. Parses model + animation XML.
2. Builds a "transform tree" and replays every animation frame through it to find nodes
   whose relative transforms never diverge, then **merges compatible meshes** (same
   texture/flags/properties, never move relative to each other) to cut draw calls. This
   is why a compiled prop usually has far fewer meshes than the mxml has `triMesh` elements.
3. Creates the live fork scenegraph (`Model`/`ModelNode`/`ModelMesh`/`SkinMesh`), runs
   vertex-cache optimization (Forsyth) on each mesh, recenters vertices on bound centers.
4. Creates `Model.Animation` objects (targets resolved to node references, transforms
   packed) and instantiates/configures `ModelController`s from the properties.
5. Prunes unused nodes, applies root `scale`, serializes with the fork's
   `BinaryExporter` to `model.dat`.

The compiler runs headless against `com.jme.util.DummyDisplaySystem`.

## 2. The binary format (`model.dat`)

Written/read by the fork's jME binary capsule system (`com.jme.util.export.binary`),
**not** interchangeable with jME3's `.j3o` (different class table, different classes).
`Model.readFromFile` → `BinaryImporter.load` → class-driven `read(JMEImporter)`:

- **`ModelNode`** (and `Model` root): `name`, `localTranslation` (Vector3f Savable),
  `localRotation` (Quaternion), `localScale`, `children` (Savable list — recursion).
- **`Model`** adds: `propNames[]`/`propValues[]` (the whole properties file),
  `animNames[]`/`animValues[]` (Animations), `controllers` (Savable list).
- **`Model.Animation`**: `frameRate` (int fps), `repeatType` (Controller.RT_*),
  `staticTargets` (Spatial refs — nodes that are visible during the animation but never
  move), `transformTargets` (Spatial refs), `transforms` — a packed FloatBuffer of
  10 floats (translation 3, quaternion 4, scale 3) × targets × frames.
- **`ModelMesh`** (extends fork `TriMesh`): `name`, local TRS, `modelBound`,
  `vertexBuffer` (3f), `normalBuffer` (3f), `textureBuffer` (2f, single UV set, may be
  null), `indexBuffer` (int triangles), then texture/render config: `textureKey` (name
  from the mxml, used as property key), `textures[]` (resolved file name(s); >1 ⇒ random
  per-instance pick), `sphereMapped`, `filterMode`, `mipMapMode`, `compress`,
  `emissiveMap`, `emissive`, `additive`, `solid` (backface culling), `transparent`,
  `alphaThreshold`, `translucent` (runtime CPU triangle depth-sort).
- **`SkinMesh`** (extends ModelMesh): adds `weightGroups[]`; each `WeightGroup` =
  `vertexCount` + `bones[]` (refs to `ModelNode`s) + interleaved `weights[]`. Vertices
  are pre-sorted so each group is a contiguous run.

Texture *contents* are never embedded — only names. At runtime
`Model.resolveTextures(TextureProvider)` maps each name to a texture state;
`ModelCache.ModelTextureProvider` resolves `name` → `<model dir>/<name>` (or absolute
from `rsrc/` if prefixed with `/`), via `TextureCache`, optionally applying nenya
`Colorization[]` recolorings and a detail-level scale.

**Deserialization classpath hazard:** controllers are serialized by class name, and 61
models reference `com.threerings.bang.game.client.sprite.*Emission` classes that live in
`client/shared` — reading those `model.dat` files requires the game classes, not just
`app/`. (This matters for any converter: it must run with `client:shared` on the
classpath. `assets` already depends on `client:shared` for exactly this kind of reason.)

## 3. Animations: keyframe node transforms, two mesh flavors

There are **no morph-target animations in the stored format**. Everything is keyframed
node ("bone") transforms, sampled at every frame:

- **Rigid animation** (most animated props/bonuses, vehicle units): meshes are parented
  to animated `ModelNode`s; each frame the `Animation` slams TRS onto its
  `transformTargets` (with lerp/slerp blending between frames).
- **Skinned animation** (bipeds/quadrupeds — gunslinger, cavalry, buffalo...): same node
  animation, plus `SkinMesh` deforms vertices from bone world transforms per weight group.

`Model.AnimationMode` (SKIN / MORPH / FLIPBOOK) is a **runtime strategy**, not a storage
property: `ModelCache` picks SKIN (with a GLSL skinning shader on NVIDIA, CPU otherwise)
on medium+ detail, otherwise MORPH, where per-frame vertex buffers are baked lazily at
runtime and blended (FLIPBOOK = same baking without blending). So "morph" in this code
base means "runtime-baked cache of the bone animation", and a jME3 port only needs to
reproduce the *bone* animation.

Procedural controllers serialized in `model.dat` (all extend fork `Controller`):

| Class | Behavior |
|---|---|
| `c.t.jme.model.Rotator` | spins target around an axis at constant velocity |
| `c.t.jme.model.Translator` | translates target cyclically |
| `c.t.jme.model.BillboardController` | orients target to camera plane |
| `c.t.jme.model.TextureAnimator` | flipbook within a texture (UV frame switching) |
| `c.t.jme.model.TextureTranslator` | scrolls UVs at constant velocity (flames, chains) |
| `c.t.bang.game.client.sprite.{Gunshot,DudShot,Misfire,Particle,SmokePlume,TransientParticle}Emission` | game-event-driven effect emitters keyed to animation frames; spawn transient geometry under `Model.getEmissionNode()` |

## 4. Avatar composition (how it does *and doesn't* intersect)

Player avatars are **not** part of the 3D model pipeline. They are 2D paper-doll
composites: nenya `cast` bundles (`rsrc/avatars/metadata.jar` + per-town/sex
`components.jar`) feed `BundledComponentRepository` → `CharacterManager` →
`ActionFrames`, composited into a `BufferedImage` with nenya `Colorization` recoloring,
and displayed as a `BImage` in BUI (`BaseAvatarView`). None of the 310 `model.dat` files
is an avatar.

The 3D pipeline touches the same nenya machinery only at **texture resolution time**:

- `ModelCache.getModel(type, name, zations, …)` re-resolves a model instance's textures
  through `TextureCache.getTexture(path, Colorization[])` — used to tint unit textures
  (e.g. team colors) with the same `Colorization` system avatars use.
- Multi-valued texture properties give per-instance random texture selection
  (`ModelMesh.putClone` picks `properties.random % _textures.length`).

Consequence for jME3: geometry conversion is independent of avatars, but the port must
keep a runtime indirection between "texture name stored in the model" and "actual
`Texture` instance" (variant remapping + colorization + detail scaling). A baked-in
texture reference in `.j3o` covers the *default* look; colorized instances need a
runtime re-resolve step regardless of format.

## 5. The prototype converter and what it proves (Phase 5, step 2)

`tools/j3o-converter` (an isolated module — the only one with jME3 deps, wired into
`settings.gradle` as `tools:j3o-converter`, nothing depends on it) loads a compiled
`model.dat` through the existing fork loader headless (`DummyDisplaySystem` +
`Model.readFromFile`), walks the `ModelNode`/`ModelMesh` tree, and emits a jME3 `.j3o`:

- geometry: position + normal + single UV set + indexed triangles, copied buffer-for-
  buffer (no re-tessellation — the fork already did Forsyth cache optimization);
- node hierarchy and per-node local TRS (fork `Vector3f`/`Quaternion` → jME3 equivalents);
- texture path mapping mirroring `ModelCache.ModelTextureProvider` (model-relative unless
  `/`-prefixed; `foo/../bar` normalized), baked onto a placeholder `Lighting.j3md` material
  with the fork render flags carried as far as that material allows (face culling →
  `FaceCullMode`, additive/transparent → `BlendMode` + `Transparent` bucket +
  `AlphaDiscardThreshold`);
- fork-only metadata preserved as jME3 user data so nothing is lost in the round trip:
  the entire `model.properties` table (`bang.properties`), `bang.animations`, `bang.type`,
  and per-geometry `bang.textureKey` / `bang.textures` (the latter is the multi-valued
  random-pick / colorization list the runtime needs).

Multi-valued texture properties bake the *first* entry as the default and keep the full
list in user data. `SkinMesh` is converted as its base pose (weights dropped, reported).
Animations and controllers are detected, counted, and dropped with a `NOTE`. Procedural
`com.jmex.effects.particles.ParticleMesh` nodes (8 models) are skipped with a `NOTE`
rather than crashing the walk.

**Verification (the acceptance bar):** the converter re-imports its own `.j3o` through the
stock jME3 `BinaryImporter` headless and asserts per-geometry mesh-stat parity — vertex
count, triangle count, and resolved texture ref, in traversal order. Result over the
**full 310-model corpus: 310/310 PASS**. Only 2 models emit a graceful "texture not found"
warning (`extras/boom_town/barrel_fragment`'s texture name has a stray `rsrc/props/...`
mid-path; `bonuses/ghost_town/plague` points at `units/gunslinger/pistol.png` via a path
that doesn't normalize to an on-disk file) — both leave the material untextured rather
than failing. Example (`frontier_town/saloon`): 9 geometries, 1169 vertices, 711 triangles,
all texture refs — PARITY OK. `./gradlew build -x test` stays green with the module wired
in. Run it via `./gradlew :tools:j3o-converter:run --args="<abs model.dat> <abs outDir>
<abs rsrc root>"`.

## 6. Scale-out assessment

### 6.1 Coverage of the static-prop approach

Categorizing all 310 models by source features (`model.properties` `animations`/
`controllers` keys, `skinMesh` in the `.mxml`):

| Class | Count | % | Prototype fidelity today |
|---|---|---|---|
| **Fully static** (no animation, no skin, no controller) | 237 | 76% | **Complete** — geometry, UVs, transforms, textures all round-trip |
| Static geometry but with a controller | (subset of 61 ctrl) | — | Geometry complete; controller behavior dropped (see §6.2) |
| Animated (rigid or skinned) | 36 | 12% | **Base pose only** — first-frame geometry round-trips; motion dropped |
| Skinned meshes (bipeds/quadrupeds) | 27 | 9% | Base pose only; bone weights dropped |
| Models with `ParticleMesh` effect nodes | 8 | 3% | Those nodes skipped; rest of the model converts |

(Counts overlap: an animated unit is usually also skinned and may carry controllers and
particle nodes.) So the static-prop pipeline as built covers **~76% of models at full
fidelity today**, and produces a usable (motionless) `.j3o` for the remaining 24%. No
model fails to convert. The visually-important gap is the **36 animated models** (21
units, 9 extras, 3 bonuses, 3 props) and the **61 controller-driven models** — these are
the units and effects the player actually watches move, so by *salience* the gap is larger
than 24%.

Layered avatars (§4) are **out of scope entirely** — 0 of the 310 models are avatars; they
are a separate 2D paper-doll system. Nothing in this pipeline touches them. The only shared
surface is texture/colorization resolution, which the `.j3o` already defers to runtime via
the preserved `bang.textures` user data.

### 6.2 What animation conversion needs

The stored format is the easy case for jME3 (see §1.3, §3): **fully-sampled per-frame TRS
keyframes on named nodes**, no curves, no morph targets. The fork's runtime
SKIN/MORPH/FLIPBOOK modes are *runtime baking strategies* over that bone animation, not
storage — a jME3 port only has to reproduce the node animation and let jME3's own skinning
do the rest. jME3 3.9's modern `com.jme3.anim` system maps onto it almost 1:1:

| Fork construct (in `model.dat`) | jME3 3.9 target | Notes |
|---|---|---|
| `Model.Animation` (frameRate + packed 10-float TRS × targets × frames) | one `AnimClip` per animation, holding one `TransformTrack` per moving node | times[] = frame/frameRate; translations/rotations/scales[] read straight from the packed buffer. Trivial unpack. |
| `Model.Animation.transformTargets` (node refs) | the `TransformTrack` target (a `Joint`, or a `Spatial` for rigid anim) | resolve by the same node name used in the hierarchy |
| `repeatType` (clamp/wrap/cycle) | `AnimComposer` action `LoopMode` (DontLoop/Loop/Cycle) | direct enum mapping |
| `SkinMesh.weightGroups` (bones[] + interleaved weights, vertices pre-sorted into runs) | `BoneIndex` + `BoneWeight` vertex buffers (4-weight) + an `Armature`/`Joint` tree + `SkinningControl` | needs converting the named `ModelNode` bone tree to an `Armature`, and expanding the variable-width weight groups into jME3's fixed 4-influence-per-vertex layout (clamp/renormalize if any vertex has >4 — verify against the corpus) |
| Rigid animation (mesh parented to animated node, no weights) | `TransformTrack` on the `Geometry`'s parent `Node` + `AnimComposer` on the root | no skinning control needed; this is the simpler ~half of the animated set |
| `Rotator` / `Translator` (83 + small) | bake to a looping `TransformTrack`, **or** port as a small jME3 `Control` | constant-velocity spin/translate; a `Control` is faithful and cheap |
| `BillboardController` (46) | jME3 `BillboardControl` | direct equivalent exists |
| `TextureTranslator` / `TextureAnimator` (UV scroll / flipbook) | custom jME3 `Control` mutating the material's UV offset, or a small shader | no stock equivalent; small custom control |
| `*Emission` game-event effects (Gunshot/DudShot/Misfire/Particle/SmokePlume/Transient, ~64 refs) | jME3 `ParticleEmitter` / effect controls wired to game events | **not a model-format problem** — these are gameplay effect spawners keyed to animation frames; they belong with the game-event/effects port (Phase 5 step 5), not the model converter. The `.j3o` just needs to preserve the emission-node marker and frame metadata (already in `bang.properties`). |

Effort ranking: rigid keyframe animation is the cheapest win (no skinning); `TransformTrack`
unpacking is mechanical. Skinning adds the `Armature` build + 4-weight clamp. Controllers
split into "have a jME3 equivalent" (Rotator, Billboard — easy) and "custom small control"
(texture scroll). The emission controllers are explicitly deferred to the effects port.

### 6.3 Recommendation: build-time convert vs runtime loader

**Recommendation: build a runtime jME3 `AssetLoader` that reads `model.dat` directly, and
keep the `.j3o` exporter only as an optional build-time optimization / debugging aid.**

Reasoning:

- **We own both formats and the parser.** The fork's `BinaryImporter` already deserializes
  every `model.dat` headlessly today (proven: all 310 load in this converter). The
  conversion logic — fork `Spatial` tree → jME3 scenegraph — is identical whether it runs
  at build time or inside `AssetManager.loadModel`. Wrapping the exact code this prototype
  already runs in a `com.jme3.asset.AssetLoader<Model>` is a small delta and means
  **`ModelCache` keeps loading `*/model.dat` with no new build step and no asset
  duplication.** The 310 `.dat` files are already produced by the existing, untouched
  `compileModels` task.
- **Build-time `.j3o` adds a pipeline stage and a second copy of every model** (310 `.dat`
  *and* 310 `.j3o` staged/shipped), plus up-to-date wiring, for no fidelity gain — the
  geometry is byte-identical either way. It also fights the resource pipeline this plan is
  explicitly told not to disturb.
- **Fidelity is equal.** Both paths run the same conversion; neither can recover data the
  `.dat` doesn't contain. The runtime-loader path is actually *better* for the dynamic
  cases this codebase needs: colorization, multi-valued random texture pick, variant
  re-filtering, and detail-level scaling are all **runtime re-resolve steps** today
  (`Model.resolveTextures` / `ModelCache`). A baked `.j3o` would still have to defer those
  to runtime (carried in user data) — so the runtime loader removes a redundant bake step
  rather than adding one.
- **The one build-time advantage** — parse/optimize cost moved off the client and load-
  time `.j3o` being marginally faster to deserialize — is negligible here: `model.dat` is
  already a pre-optimized binary, and the corpus is 310 small models, not thousands.
- **Caveat:** a runtime loader pulls the fork's `com.jme.*` reader classes into the jME3
  client at runtime (they coexist in different packages — no clash), and the
  classpath-hazard from §2 stands: the 61 controller-referencing `model.dat` need the
  `client:shared` emission classes on the classpath to deserialize. They already are at
  runtime, so this is free for the runtime loader but would be an extra dependency for a
  standalone build-time tool.

Net: the runtime `AssetLoader` is less build complexity, equal fidelity, fewer moving
parts, and aligns with the existing `ModelCache`/colorization indirection. Use this
prototype's exporter for spot-checking and for any future "freeze a model to pure jME3"
need, but make the loader the primary path.

### 6.4 Effort estimate for full coverage

Building on this prototype (geometry/UV/texture/static = done, 76% at full fidelity):

| Work item | Estimate |
|---|---|
| Wrap the converter as a runtime `AssetLoader<Model>` + integrate with a jME3 `ModelCache` (texture re-resolve, colorization, variant filtering, multi-texture random pick) | 3–5 agent-days |
| Rigid keyframe animation → `AnimClip`/`TransformTrack` + `AnimComposer` (the ~half of animated models with no skinning) | 2–3 agent-days |
| Skinning: `ModelNode` bone tree → `Armature`/`Joint`, weight-group → 4-influence `BoneIndex`/`BoneWeight`, `SkinningControl`; verify max-influence clamp against the 27 skinned meshes | 4–6 agent-days |
| Procedural controllers with jME3 equivalents (Rotator, Billboard, texture scroll/flipbook) | 2–3 agent-days |
| Emission controllers (Gunshot/Misfire/SmokePlume/…) — **deferred to the effects/game-event port** (Phase 5 step 5), not counted here | (separate) |
| Material upgrade beyond the placeholder (emissive/sphere-map/additive/translucent CPU-sort fidelity vs jME3 materials) + per-town visual regression pass | 3–5 agent-days |

**Model pipeline to full coverage (excluding the effects port): ~2–3 agent-weeks**,
gated by human visual review. The prototype has retired the format-and-parity risk for the
bulk of the corpus; the remaining work is animation/skinning plumbing onto jME3's
`com.jme3.anim` system, which the stored format maps onto cleanly.
