# BUI → jMonkeyEngine 3.9 port: coupling analysis & port shape

Phase 5 step 3 groundwork (see UPGRADE_PLAN.md). This documents exactly where the `bui/`
module touches the vendored jME fork (`com.jme.*`), the decision on how to port it, and the
render-backend seam introduced to make the port incremental.

## Scope of the problem

- `bui/` is 88 Java files; **32 import `com.jme.*`**, 12 call LWJGL `GL11`/`Display`/`Mouse`
  directly.
- BUI is consumed by **196 files** in `app/` + `client/` (`com.jmex.bui.*` imports).
  Critically, client code doesn't just *use* widgets — it **extends the render path**:
  42 overrides of `renderComponent(com.jme.renderer.Renderer)` in `client/shared` draw
  custom content (avatar views, palette icons, tab strips, posters) using `BImage.render`
  and occasionally raw `GL11`. The `Renderer` parameter type and `BImage` are part of BUI's
  de-facto public API.

## Coupling catalog

Four distinct kinds of coupling, with very different port costs:

### 1. Type tokens in public signatures (cheap, mechanical at final port)

`com.jme.renderer.Renderer` appears as a pass-through parameter in every `render()`
signature (25 files: `BComponent`, `BIcon`+4 impls, `BBackground`+3 impls, `BBorder`+3
impls, `BText`/`BTextFactory`/`AWTTextFactory`/`HTMLView`, `Label`, all widgets).
`com.jme.renderer.ColorRGBA` is the color type in `BStyleSheet`, `BComponent._colors`,
text factories, `LineBorder`, `TintedBackground` (12 files). Neither is *called into*
in most files — they are tokens threaded through. At the final port these become
`com.jme3.*` equivalents via a mechanical, repo-wide type swap (client/shared moves at the
same time in the Phase-5 "core client" step, so the signature change is coordinated, not
breaking).

### 2. Behavioral render code (the real work — now isolated behind the seam)

| File | Engine usage |
|---|---|
| `BImage` | The workhorse: `extends com.jme.scene.Quad`; builds `com.jme.image.Image` from AWT pixels; `Texture`/`TextureState` creation via `DisplaySystem.getRenderer()`; static `AlphaState` blend (src-alpha / one-minus-src-alpha); `TextureManager.hasAlpha`; NPOT capability probe via `GLContext`; draw via quad translate + `draw(renderer)`; texture pool acquire/release (pool implemented by client `BangUI`). |
| `BComponent` | `glTranslatef(±x,±y)` coordinate-space push/pop around child painting (9 GL calls); static scissor helpers `intersectScissorBox`/`restoreScissorState` (`glScissor`, `glGetInteger`); `applyDefaultStates()` via `Renderer.defaultStateList` + `RenderContext`. |
| `BRootNode` | `extends com.jme.scene.Geometry`; renders in `Renderer.QUEUE_ORTHO` via `onDraw`→`checkAndAdd`→`draw`; modal shade = immediate-mode `GL_QUADS` (7 GL calls); `DisplaySystem` width/height for tooltip layout; no-op collision overrides. |
| `BGeomView` | 3D-scene-inside-UI widget: swaps `renderer.unsetOrtho()`/`setOrtho()`, private `Camera` with custom viewport, scissored `glClear(DEPTH)`, `renderer.draw(spatial)`, restores GL translation. Most fork-specific file in BUI. |
| `LineBorder` | Immediate-mode `GL_LINE_STRIP` rect outline (8 GL calls) + fork `LineRecord` line-width state. |
| `TintedBackground` | Immediate-mode solid `GL_QUADS` fill (7 GL calls). |
| `BTextField` | Text cursor = immediate-mode `GL_LINE_STRIP` vertical line (5 GL calls). |
| `Label`, `BScrollPane`, `BScrollingList` | `glTranslatef` offsets only (2 GL calls each). |
| `BWindow`, `BPopupWindow`, `BPopupMenu` | `DisplaySystem` width/height for centering/clamping (2 call sites each). |

### 3. Input & windowing (small, well-contained)

| File | Engine usage |
|---|---|
| `PolledRootNode` | The only input entry point: registers a `KeyInputListener` + `MouseInputListener` with the fork's `KeyInput`/`MouseInput` singletons (which the gdx layer feeds — key codes are **gdx `Input.Keys`** codes already, see `KEY_MODIFIER_MAP`), pumps `KeyInput.get().update()` per frame, falls back to the fork `InputHandler` when no component has focus (that's how camera control receives input when UI doesn't consume it), `com.jme.util.Timer` for event timestamps, `org.lwjgl.opengl.Display.isActive()` for focus-loss modifier reset, key-repeat synthesis. |
| `BCursor` | Pure LWJGL2 (`org.lwjgl.input.Cursor/Mouse`), no jME at all. Hardware cursors from `BufferedImage`. **Leaks `org.lwjgl.input.Cursor` to client** (`BangBoardView.getCursor()`), so it stays fork-side until the windowing layer moves to LWJGL3/GLFW (`GlfwCursor` in jME3-lwjgl3). |

### 4. Singletons

`DisplaySystem.getDisplaySystem()` (8 files, mostly `getWidth/getHeight`, plus
`getRenderer().createTextureState/createAlphaState` in `BImage` and
`getCurrentContext()` state records in `BComponent`/`LineBorder`). jME3 has no equivalent
singleton — the seam owns "current display size" and state application instead.

### Text rendering: no engine coupling at all

`AWTTextFactory` (the only `BTextFactory` used; `BangUI` constructs it) renders text with
**java.awt.font.TextLayout into a `BufferedImage`**, including the outline/shadow/glow
effects and the `@=b(...)` style syntax, then wraps it in a `BImage`. Hit-testing and caret
positions come from the AWT `TextLayout`, not from GL. `HTMLView` similarly paints
`javax.swing.text` into an image. **Fonts port for free**: the same BufferedImage becomes a
jME3 `Image(Format.RGBA8)`/`Texture2D`. jME3 `BitmapText` is *not* needed and would be a
regression (different metrics → relayout of every screen, loss of the styled-text effects,
font-asset conversion). The only per-backend work is the pixel upload path in `BImage`.

## Port shape decision: (a) reimplement BUI internals on jME3 — confirmed

UPGRADE_PLAN.md leaned (a) with Lemur as alternative (b). The analysis settles it:

**Choose (a).** Evidence:

1. **The frozen API includes a paint hook.** 42 `renderComponent(Renderer)` overrides in
   client/shared draw arbitrary content (images, sub-rects, raw GL) mid-traversal. Lemur is
   retained-mode (Panels composed of GuiComponent layers); it has no "paint yourself now at
   this offset with this scissor" hook. Mapping BUI onto Lemur breaks every one of those 42
   overrides plus `BGeomView` (3D viewport inside UI — no Lemur equivalent), `HackyTabs`,
   the modal-shade/window-layer system, tooltips, and the BUI event system
   (global listeners, default event targets, focus model) that client code hooks
   (`BangApp` overrides `PolledRootNode.dispatchEvent` for UI sounds).
2. **BUI's actual render needs are tiny.** The entire backend surface is: textured quad,
   solid quad, line/rect outline, translate stack, scissor stack, display size, pixel
   upload, input events. That is a few hundred lines on jME3
   (`RenderManager.renderGeometry` with an Unshaded material in the GUI viewport /
   a `SceneProcessor`, `Renderer.setClipRect` for scissor).
3. **Text ports for free** under (a) (see above); under (b) it's a rewrite with visual
   regressions.
4. **Styles**: `BStyleSheet` parses BUI's own `.bss` format used by all of
   `rsrc/config/ui/*`; Lemur styling is an incompatible attribute system — porting style
   data is pure cost with zero gain.
5. Lemur would also add a new third-party dependency and its own GuiGlobals/InputMapper
   machinery that overlaps with what `app/` already does.

Lemur remains sensible only for a hypothetical *rewrite* of the UI, not for keeping the
existing 196-consumer API frozen.

### jME3 implementation strategy (for the new backend)

- BUI keeps its immediate-mode painter model. The jME3 backend renders during the render
  phase (a `SceneProcessor` on the GUI viewport, or an explicit hook from the app loop),
  issuing `RenderManager.renderGeometry` calls on pooled `Geometry` instances with
  `Unshaded.j3md` materials; the seam's translate stack becomes the geometry world
  translation; the scissor stack maps to `Renderer.setClipRect`/`clearClipRect`.
- `BImage`'s superclass (`com.jme.scene.Quad`) is **GL-free at construction** (NIO buffers
  only — verified in the fork source), so `BImage` instances are constructible even when
  the fork renderer never initializes; all engine-touching work happens in the backing
  (this is what makes the dual-backend seam viable while `bui` still compiles against the
  fork).
- Draw ordering: BUI sorts windows itself and paints back-to-front; the jME3 backend keeps
  GPU state flat (no depth test, alpha blend on) exactly like the ortho queue does today.
- Input: a jME3 `RawInputListener` maps 1:1 onto what `PolledRootNode`'s
  `KeyInputListener`/`MouseInputListener` receive today (BUI already uses gdx keycodes;
  the jME3 backend translates jME3 keycodes the same way the gdx layer does now).
- `BGeomView`: becomes a dedicated jME3 `ViewPort` rendered into the component's screen
  rect (jME3 supports multiple viewports natively — *simpler* than the fork version).
- Texture pool (`BImage.TexturePool`, implemented by client `BangUI`): fork-typed API
  (`TextureState` parameter). The jME3 backend starts with the trivial create/delete pool;
  the client pool gets retyped at final port. **Flagged API exception.**
- `BCursor`: stays LWJGL2 until windowing moves (Phase 5 step 6); jME3 demo uses the
  default cursor. **Flagged API exception** (`getCursor()` returns an LWJGL type).

## The render-backend seam (implemented in this branch)

New package `com.jmex.bui.backend`:

- **`BRenderBackend`** — engine-neutral interface covering every *behavioral* touchpoint:
  display size, window-active probe, default/blend state application, translate stack,
  solid rect, rect outline, line, scissor intersect/restore, scissored depth clear, and
  `BImageBacking` creation.
- **`BImageBacking`** — per-image backend object: pixel upload (`setImage` with raw
  RGB(A) bytes), texture-coordinate sub-rects, draw-at-rect, acquire/release (texture
  pooling). `BImage` retains its public API (including `extends Quad`) and delegates all
  engine work to its backing.
- **`BackendProvider`** — static holder; defaults to the fork backend so **nothing changes
  for the shipped game**. A different backend (jME3) is installed explicitly before any UI
  class loads.
- **`JmeRenderBackend` / `JmeImageBacking`** — the existing fork/GL11 code, moved verbatim.

Deliberately *not* behind the seam (documented exceptions): type tokens
(`Renderer`/`ColorRGBA`/`Spatial` in signatures — final-port mechanical swap), scene-graph
inheritance (`BRootNode extends Geometry`, `BImage extends Quad` — inert off-fork),
`BGeomView`'s camera dance (fork-only; jME3 gets a parallel implementation),
`PolledRootNode` (fork input glue; jME3 gets a sibling root node), `BCursor` (LWJGL2
windowing, moves with step 6).

## jME3 backend status & remaining work

See `Jme3RenderBackend` / `Jme3ImageBacking` (compileOnly dep on
`org.jmonkeyengine:jme3-core:3.9.0-stable`, kept off the game's runtime classpath —
LWJGL2/LWJGL3 natives must not meet; verified the client deploy still ships only the fork
jme jar + LWJGL 2.9.2).

**Implemented and compiling** against jme3-core 3.9.0-stable: textured quads (the full image
pipeline, including the AWT-rendered styled text from `AWTTextFactory` — the fork
`com.jme.image.Image`'s AWT-raster BGR/ABGR bytes map onto jME3 `BGR8`/`ABGR8` with no
channel reshuffle), solid rects, rect outlines (four edge rects), lines (thin rects),
the translate stack (accumulated into geometry world translation), scissor via
`Renderer.setClipRect`/`clearClipRect`, and display size. Each primitive is a transient
`Geometry` (Quad + Unshaded material, alpha blend, depth-test off) drawn through
`RenderManager.renderGeometry`. The host installs the backend with an `AssetManager` and
brackets each frame with `beginFrame(RenderManager,w,h)` / `endFrame()`.

**Not yet implemented** (honest stopping point for step 3 — these need the rest of Phase 5
to be exercisable at all):
1. **Input** — no `Jme3RootNode` yet. `PolledRootNode` is fork/LWJGL2 input glue; the jME3
   sibling (a `RawInputListener` mapping jME3 keycodes the way the gdx layer maps them today)
   lands with the core-client port when there is a jME3 application loop to attach it to.
2. **A runnable demo** — a test-scoped jME3 `SimpleApplication` showing a `BWindow` needs the
   jME3 + LWJGL3 runtime and a display, which conflict with the shipped LWJGL2 stack; it only
   becomes meaningful once core-client (step 4) provides the jME3 host.

Remaining for a complete jME3 BUI (estimate):
1. Type-token swap (`Renderer`→`RenderManager`, `ColorRGBA`, etc.) across bui + app +
   client/shared — mechanical, big diff (~2–3 agent-days incl. fixing the 42 overrides).
   The seam means BUI's *internals* are already off the fork; this swap is about the
   pass-through token types in signatures.
2. `Jme3RootNode` + input listener; wire `beginFrame`/`endFrame` into the jME3 app loop.
3. `BGeomView` on jME3 ViewPorts (~1 day).
4. Hardware cursors + window glue once LWJGL3 lands (~1 day).
5. Texture-pool rework + client `TexturePool` retype (~0.5 day).
6. Visual regression pass over every screen (the long pole — needs the rest of Phase 5,
   since real screens embed avatars/models).
