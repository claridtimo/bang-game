# Runtime jME3 model loader (Phase 5, step 2 continued)

Builds on `docs/jme3-model-pipeline.md` (format analysis + the build-time-vs-runtime
recommendation). That doc recommended a **runtime jME3 `AssetLoader` reading `model.dat`
directly** over a build-time `.j3o` bake, because the fork's `ModelCache` re-resolves
colorization / variant / detail at instance time. This document describes the loader that
implements that recommendation, the ModelCache-equivalent design, and what is implemented
versus designed-only.

All code lives in the isolated `tools/j3o-converter` module — the only module with jME3
deps, depended on by nothing. The fork's `com.jme.*` reader classes and jME3's `com.jme3.*`
classes coexist in different packages with no clash. **Nothing in the shipped game (which
still runs on the fork/LWJGL2) references this module.**

## 1. End-to-end: what the loader does

```
model.dat  ──fork BinaryImporter──▶  fork Model (ModelNode/ModelMesh/SkinMesh tree)
           ──ModelConverter────────▶  jME3 Spatial (Node + Geometry + Material
                                       + AnimComposer + SkinningControl/Armature)
```

Classes (`com.threerings.bang.jme3.model`):

- **`BangModelLoader implements com.jme3.asset.AssetLoader`** — the runtime entry point.
  `assetManager.registerLoader(BangModelLoader.class, "dat")` then
  `assetManager.loadModel("units/frontier_town/gunslinger/model.dat")` returns a jME3
  `Spatial`. It reads the `model.dat` stream with the fork `BinaryImporter`
  (`Model.readFromFile`-equivalent), derives the model's `typePath` from the asset key, and
  delegates conversion. A headless `loadModel(AssetManager, File, String)` overload skips the
  `AssetInfo` plumbing for tools/tests (the exporter and corpus verifier use it).
  - It installs a `DummyDisplaySystem` **once, before any `DisplaySystem.getDisplaySystem()`
    call** — the fork's default `SystemProvider` is the gdx one, whose `GDXDisplaySystem`
    constructor dereferences `Gdx.input` (null with no gdx app) and NPEs. Installing the dummy
    provider first makes the whole fork reader headless. (This was the one real gotcha; the
    prototype side-stepped it by constructing the dummy unconditionally at `main` start.)

- **`ModelConverter`** — the shared conversion engine (used by both the runtime loader and the
  build-time `ModelToJ3o` exporter, so they can never diverge). For one fork `Model` it produces:
  - **Geometry** — position/normal/single-UV/index buffers copied straight from each
    `ModelMesh` (the fork already Forsyth-optimised them; no re-tessellation).
  - **Hierarchy + transforms** — every `ModelNode` → a jME3 `Node` carrying the fork local TRS
    (fork `Vector3f`/`Quaternion` → jME3 equivalents).
  - **Material** — a placeholder `Lighting.j3md` with the default texture resolved through
    `ModelTextureResolver` (see §2); fork render flags carried as far as that material allows.
  - **Rigid keyframe animation** — each fork `Model.Animation` → one `AnimClip` of
    `TransformTrack`s targeting the moving `Node`s, on an `AnimComposer` added to the root.
  - **Skinning** — `SkinMesh` weight groups → 4-influence `BoneIndex`/`BoneWeight` buffers over
    an `Armature` of `Joint`s (the referenced bone `ModelNode`s), driven by a `SkinningControl`;
    the same `AnimClip`s drive the joints (a track whose fork target is a bone node binds to the
    `Joint`, otherwise to the `Node`). See §3.
  - A `Result` recording per-geometry stats, converted-animation names, skinned-mesh count,
    armature joint count, track count, detected-but-dropped controller class names, skipped
    procedural spatials, and whether any vertex needed influence clamping.

- **`ModelTextureResolver`** — the ModelCache-equivalent (see §2).

- **`ModelToJ3o`** (build-time exporter) and **`VerifyCorpus`** (full-corpus parity/coverage
  harness) sit on top of the shared converter.

## 2. The ModelCache-equivalent (`ModelTextureResolver`)

The fork's `ModelCache.ModelTextureProvider` is the runtime indirection between a texture
**name** stored in a model and the actual `TextureState` an instance renders with. It does
three runtime re-resolution steps; here is how each maps onto jME3.

### 2.1 Texture path mapping — IMPLEMENTED
A stored name is relative to the model directory unless it starts with `/` (then rsrc-relative);
`foo/../bar` segments are normalised (mirrors `ModelCache.cleanPath`). `textureAssetPath(typePath,
name)` reproduces this exactly; verified across the corpus (only the two known stray-path models
— `extras/boom_town/barrel_fragment`, `bonuses/ghost_town/plague` — resolve to a missing file and
are left untextured rather than failing, same as the prototype).

### 2.2 Variant / random texture pick — IMPLEMENTED (pick) + DESIGNED (variant filter)
A multi-valued `<texture>` property means "choose one texture per instance"
(`ModelMesh.putClone` picks `properties.random % _textures.length` — e.g. cow-hide variations).
`ModelTextureResolver` takes a `variantIndex` (the analogue of `CloneCreator.random`) and selects
`textures[variantIndex % textures.length]`; single-valued lists take the only entry. **The full
texture list and texture key are preserved as `bang.textures` / `bang.textureKey` user data on
each `Geometry`**, so an instance can re-pick or re-resolve without reloading the model.

*Variant configurations* (`Model.createPrototype(variant)` re-filters all mesh properties by a
`<variant>.` prefix — 4 models) are **designed**: the loader preserves the whole properties table
as the root's `bang.properties` user data, so a jME3 `ModelCache` can, per requested variant,
re-derive the per-mesh texture/flag overrides and re-run `applyMaterial` on a cloned geometry.
Implementing the prefix re-filter is a few lines once a jME3-side cache instance API exists; it is
not implemented here because it needs that cache's instance/variant request shape (cutover work).

### 2.3 Colorization (nenya `Colorization[]`) — DESIGNED, hook implemented
Unit textures are tinted at instance time (team colours, etc.) by nenya `Colorization`s, the same
recolouring system avatars use, via `TextureCache.getTexture(path, Colorization[])`. Avatars
themselves are **not** part of this pipeline (they are a separate 2D paper-doll system — `docs/
jme3-model-pipeline.md` §4); the only shared surface is this texture-recolour step.

Two concrete jME3 designs, both fed by the preserved `bang.textures` user data:

1. **CPU recolour on a cloned `Image`** (faithful, matches the fork): a `Colorization` remaps a
   source palette's hue/saturation ranges. Port nenya's `Colorization.recolorImage` onto a copy
   of the jME3 `Texture`'s `Image` (`ByteBuffer` pixels), cache the recoloured `Texture` keyed by
   `(path, zations)`. `ModelTextureResolver.colorize(Texture)` is the seam (currently identity).
   Per-instance colorised units clone the affected `Geometry` and swap the `DiffuseMap`.
2. **GPU recolour via a MatDef** (cheaper at scale): pass the colorization zones/target colours as
   material params to a small recolouring `.j3md` that does the palette remap in the fragment
   shader; no per-instance texture copies. Preferred long-term.

Either way the colorization carrier (`Colorization[]` equivalent) is supplied to the resolver at
instance-resolve time, exactly as `ModelCache.getModel(type, name, zations, …)` does today. Only
the identity hook is wired now; the pixel/shader recolour is the colorization port's job.

### 2.4 Detail-level scaling — DESIGNED
The fork scales texture resolution by a runtime detail level. In jME3 this is a `TextureKey`/
`AssetManager` concern (mip bias, or loading a downscaled variant); it slots into
`applyMaterial`'s `loadTexture` call (choose the key by detail). Not implemented (no perf need at
310 small models; it is a one-line key choice when wanted).

### 2.5 Render flags — IMPLEMENTED (as far as `Lighting.j3md` allows)
`solid`→`FaceCullMode`, `additive`→`BlendMode.AlphaAdditive`+Transparent bucket,
`transparent`→`BlendMode.Alpha`+`AlphaDiscardThreshold`+Transparent bucket, `emissive`→`GlowColor`.
`sphereMapped`/`translucent` (CPU triangle depth-sort) have no clean placeholder-material
equivalent and are deferred to the material-upgrade pass (carried in user data, not lost).

## 3. Animation + skinning conversion

The stored format is the easy case (`docs/jme3-model-pipeline.md` §1.3, §3): **fully-sampled
per-frame TRS keyframes on named nodes**, no curves, no morph targets. jME3 3.9's `com.jme3.anim`
maps onto it ~1:1.

### 3.1 Rigid keyframe animation — IMPLEMENTED
Each `Model.Animation` (`frameRate` + packed 10-float TRS × targets × frames) becomes one
`AnimClip` of `TransformTrack`s: `times[ff] = ff/frameRate`; translations/rotations/scales read
straight from the packed `Transform` buffer (unpacked via `ModelAnimAccess`, which exposes the
protected TRS fields). Tracks whose target is a plain (non-bone) `Node` drive rigid animation
directly; the clip set hangs off one `AnimComposer` on the root. `repeatType` (clamp/wrap/cycle)
maps to `AnimComposer` action `LoopMode` and is preserved in user data for the player wiring.

### 3.2 Skinning — IMPLEMENTED
- **Bone tree → Armature.** The set of `ModelNode`s referenced by any `SkinMesh.WeightGroup` is
  collected; each becomes a `Joint` carrying the fork node's local TRS. A joint's parent is its
  *nearest bone ancestor* in the fork hierarchy (intermediate non-bone nodes are skipped), so the
  `Armature` mirrors the skeleton. `saveInitialPose()` is called.
- **Weight groups → 4-influence buffers.** The fork pre-sorts vertices into contiguous runs
  (weight groups), each naming its own bone set with interleaved weights, and the deformer walks
  groups in buffer order — so weight-group vertex N maps to mesh vertex N linearly. The converter
  expands each group's variable bone count into jME3's fixed 4 `BoneIndex` (`UnsignedByte`,
  ≤256 bones/mesh holds across the corpus) + `BoneWeight` (`Float`) slots, **keeping the 4 highest
  weights and renormalising** when a vertex has >4 influences. `setMaxNumWeights(4)` +
  `generateBindPose()` produce the bind-pose buffers jME3 skinning needs.
- **Index remap.** Buffers are first built against a per-mesh local bone order, then remapped to
  global `Armature` joint indices once the armature exists.
- **Driving it.** A single `SkinningControl(armature)` on the root deforms all skinned geometries
  beneath it; the same `AnimClip`s drive the joints (a track whose fork target is a bone node binds
  to that `Joint`).

Corpus result: **27 skinned models, 50 skinned meshes, 801 armature joints**; **4 models have
some vertex with >4 influences** (clamped + renormalised — flagged in the `Result`, worth a visual
check at the material pass but geometrically sound).

### 3.3 Procedural controllers — DETECTED, deferred (correct boundary)
`Rotator`/`Translator`/`BillboardController`/`TextureAnimator`/`TextureTranslator` and the
`*Emission` game-effect controllers (61 models) are detected and reported by class name, **not**
converted. The emission controllers are gameplay effect spawners keyed to animation frames and
belong with the effects/game-event port (Phase 5 step 5), not the model loader — they need the
emission-node marker + frame metadata, both already preserved in `bang.properties`. Rotator/
Billboard/texture-scroll have jME3 equivalents (`BillboardControl`, small custom `Control`s) and
are cheap follow-ups, intentionally out of scope for this additive step.

### 3.4 Procedural geometry — SKIPPED, reported
Non-mesh procedural spatials (`com.jmex.effects.particles.ParticleMesh`, 8 models) are skipped
with a report rather than crashing the walk; the rest of each such model converts.

## 4. Verification

`VerifyCorpus` walks every `model.dat` under the staged rsrc tree (produced by the **untouched**
`compileModels` step), loads each through `BangModelLoader`, **exports the converted graph to
`.j3o` and re-imports it through the stock jME3 `BinaryImporter`**, and asserts per-geometry
mesh-stat parity (vertex count, triangle count, resolved texture ref, in traversal order). Run:

```sh
./gradlew :assets:compileModels                 # stage the 310 model.dat (no game changes)
./gradlew :tools:j3o-converter:verifyCorpus     # parity + coverage over the full corpus
```

Full-corpus result (310 models):

| Metric | Value |
|---|---|
| Parity | **310 / 310 PASS** |
| Fully static (no anim, no skin) | 273 |
| Animated (AnimClips on AnimComposer) | 36 |
| Skinned (Armature + SkinningControl) | 27 |
| With controllers (detected, deferred) | 61 |
| With skipped procedural geom (ParticleMesh) | 8 |
| Influence-clamped (>4 bones/vertex) | 4 |
| Geometries | 941 |
| AnimClips | 201 |
| TransformTracks | 6597 |
| Skinned meshes | 50 |
| Armature joints | 801 |

(The 273 "static" here means *not animated and not skinned*; the 61 controller-carrying models are
static geometry whose controllers are deferred — `docs/jme3-model-pipeline.md` §6.1 counted those
separately, giving its 237 "no anim/skin/controller" figure.)

## 5. Implemented vs designed-only, and remaining cutover effort

**Implemented and corpus-verified:** geometry/UV/transform conversion; texture path mapping +
multi-valued random pick; render flags onto the placeholder material; rigid keyframe animation
(`AnimClip`/`TransformTrack`/`AnimComposer`); skinning (`Armature`/`Joint`/4-influence buffers/
`SkinningControl`); the runtime `AssetLoader`; full-corpus parity + coverage harness.

**Designed-only (with a concrete hook/plan, not wired):** colorization recolour (CPU `Image` clone
or GPU MatDef — `colorize()` seam present); variant prefix re-filtering (properties preserved);
detail-level texture scaling; `sphereMapped`/`translucent` material fidelity; procedural controllers
with jME3 equivalents (Rotator/Billboard/texture-scroll); emission controllers (effects port).

**Remaining effort to wire into a jME3-hosted client at cutover:**
1. Register `BangModelLoader` on the client `AssetManager` and re-point `ModelCache.getModel(...)`
   at it; thread the `variantIndex`/`Colorization[]`/detail args from the existing call sites into
   `ModelTextureResolver` (the indirection shape is already there). ~1–2 days.
2. Implement the colorization recolour behind the `colorize()` seam (CPU or MatDef) — folds into
   the colorization port. ~2–3 days.
3. Material upgrade beyond the placeholder `Lighting.j3md` (emissive/sphere-map/additive/
   translucent CPU-sort fidelity) + per-town visual regression. ~3–5 days (gated by visual review).
4. Procedural controllers with jME3 equivalents (Rotator/Billboard/texture-scroll). ~2–3 days.
   Emission controllers are the effects port (separate).

The format-and-parity risk for the whole corpus — including animation and skinning — is retired:
all 310 models, with their animation and skin data, convert and round-trip through jME3 today.
The remaining work is integration (wiring the loader into the live client at the LWJGL3 cutover)
and material/effect fidelity, both gated by human visual review.
