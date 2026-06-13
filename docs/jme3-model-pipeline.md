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
