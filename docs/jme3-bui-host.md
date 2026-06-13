# jME3 BUI host harness (Phase 5 step 3 proof)

This closes the loop left open by the BUI render-backend seam (PR #4, see
`docs/jme3-bui-port.md`): the seam added a compile-only `Jme3RenderBackend`/`Jme3ImageBacking`
that **nothing had ever instantiated**. This document describes the isolated jME3 host that
installs that backend and renders a real BUI scene through it, what rendered, the bugs found
standing it up, and the concrete remaining work to host the actual Bang client UI on jME3.

## What was built

A new **isolated** Gradle module `tools:jme3-host` (sibling of `tools:j3o-converter`, the
existing isolated jME3 module). It owns its own jME3 + LWJGL3 runtime and its own `run` task:

```sh
DISPLAY=:1 ./gradlew :tools:jme3-host:run
```

Nothing in the shipped build depends on it. Verified isolation:
`./gradlew :client:desktop:dependencies --configuration runtimeClasspath` shows **no**
`jmonkeyengine`/`jme3`/`lwjgl3` artifacts, and `:bui`'s runtime classpath has no `jme3-core`
(it stays `compileOnly`). The shipped game still runs on LWJGL2.

### Classpath coexistence (why this is safe)

`tools:jme3-host` reads `:bui`, which transitively pulls the fork `jme` jar (and LWJGL 2.9).
So at **compile and class-load time both engines are on the classpath** — BUI's public API
still threads fork type tokens (`com.jme.renderer.Renderer`/`ColorRGBA`,
`com.jme.image.Image`) through its signatures, and `BImage extends com.jme.scene.Quad`. That
is fine because:

- The fork `Quad`/`Geometry`/`Image`/`ColorRGBA` are **GL-free data holders** at construction.
- At **runtime only jME3's LWJGL3 display is initialized**; the fork's LWJGL2 `DisplaySystem`
  is never touched, so the two native sets never meet. (The conflicting *natives* are the
  hazard, not the Java classes.)

## How the host wires the backend

`BuiHostApp` (a jME3 `SimpleApplication`):

1. **Installs the backend before building any widget**:
   `BackendProvider.set(new Jme3RenderBackend(assetManager))`. This must happen first because
   `BImage` chooses its `BImageBacking` from `BackendProvider.get()` at construction time.
   The backend is handed the app's `AssetManager` so it can load
   `Common/MatDefs/Misc/Unshaded.j3md` and build textures.
2. **Builds the BUI scene** with a self-contained `BStyleSheet` (a string fed to
   `BStyleSheet.DefaultResourceProvider`, which wires `AWTTextFactory` for every `font:` rule).
   The style uses only solid backgrounds + borders so the proof depends on nothing outside the
   `bui` jar. Scene: a `BWindow` (`GroupLayout` VStretch) containing a `BLabel`, a `BButton`,
   and a `BScrollPane` whose 20-row child overflows the 320x140 pane.
3. **Uses a custom `BRootNode`** (`BuiRootNode`). `BRootNode` is abstract and still
   `extends com.jme.scene.Geometry` (a documented Phase-5 exception); the host subclass does
   **not** use the fork `onDraw`/`draw` ortho-queue path. Instead `renderWindows()` walks the
   protected window list and calls `BWindow.render(null)` — the `com.jme.renderer.Renderer`
   argument is a pass-through token only; BUI's actual drawing is routed through
   `BackendProvider.get()`, i.e. the jME3 backend.
4. **Renders through a `SceneProcessor` on the GUI viewport** (`BuiProcessor`). In
   `postQueue` it brackets the window traversal with `Jme3RenderBackend.beginFrame(rm, w, h)`
   / `endFrame()`, after switching the render manager into jME3's built-in 2D ortho overlay
   (see bug #2 below), then restores the camera.

A `ScreenshotAppState` grabs a framebuffer PNG a few frames in; the app then self-terminates
(it never leaves a process running).

## What rendered (and what didn't)

Screenshot: **`/tmp/jme3-bui-fb1.png`** (the authoritative framebuffer grab, taken straight
from GL, independent of any X11 timing) and an on-screen X11 grab `/tmp/jme3-bui-host.png`.

Inspected and confirmed rendering correctly:

| BUI element | Backend path exercised | Result |
|---|---|---|
| `BWindow` background | `fillRect` (solid Quad + Unshaded `Color`) | solid blue-grey fill, correct |
| `BWindow` border | `drawRectOutline` (four edge rects) | 2px gold outline, correct |
| `BLabel` text | `AWTTextFactory` -> `BufferedImage` -> `Jme3ImageBacking.setImage` (ABGR8) -> textured Quad | **white bold styled text renders** — fonts work for free, no `BitmapText` |
| `BButton` | solid bg + border + centered text | salmon fill, white border, centered label, correct |
| `BScrollPane` | `intersectScissorBox` -> `Renderer.setClipRect` | rows 1–5 visible, rows 6–20 **clipped** — the scissor/clip path works |

**No gaps in the rendered primitive set**: solid quads, rect outlines, textured quads
(including AWT-rendered text), translate stack, and scissor clipping all produce correct
pixels. There is **no text/font gap** — the AWTTextFactory path renders to a `BufferedImage`
whose raster bytes map onto jME3 `ABGR8` with no channel reshuffle, exactly as predicted in
`docs/jme3-bui-port.md`.

### Bugs found and fixed in the (previously dead) jME3 backend

Standing up the host surfaced two real rendering bugs. Both were in code that nothing had ever
executed, so fixing them is in scope:

1. **Face culling discarded every BUI primitive.** The GUI ortho camera looks toward +Z; a
   jME3 `Quad`'s front face (CCW winding) has a +Z normal, so the camera sees the back face.
   With the default `Back` cull mode every BUI quad was culled — the geometry showed up in the
   render stats (triangle/object counts climbed) but produced **zero pixels**. Fix:
   `setFaceCullMode(FaceCullMode.Off)` on both `Jme3RenderBackend._solid` and
   `Jme3ImageBacking._material`.

2. **Coordinate space / camera.** BUI draws in pixel coordinates with a bottom-left origin.
   `RenderManager.renderGeometry` uses whatever camera is active; the GUI-viewport camera's
   own frustum here is **not** pixel-space ortho (observed `near=1, far=2, frustum [-0.5,0.5]`),
   so BUI quads landed far outside the frustum and were clipped. Fix lives in the host:
   `BuiProcessor.postQueue` calls `renderManager.setCamera(cam, /*ortho=*/true)`, which
   installs jME3's built-in 2D ortho overlay matrix (1 unit == 1 pixel, origin bottom-left),
   then restores `setCamera(cam, false)` afterward. This is the host's responsibility, not the
   backend's, so no backend change was needed for it.

These are the kind of "render-thread ordering / world-translation / scissor" issues the task
anticipated. With both fixed, the backend's primitive set is proven correct end-to-end.

## Remaining work to host the REAL Bang client UI on jME3

The harness proves the **render** path. Hosting the actual client UI needs the rest of Phase 5:

1. **Input path (`Jme3RootNode` + `RawInputListener`).** The backend has **no input path at
   all**. Today input enters BUI through `PolledRootNode`, which is fork/LWJGL2 glue: it
   registers a `KeyInputListener`/`MouseInputListener` on the fork's `KeyInput`/`MouseInput`
   singletons (fed by the gdx bridge, already using **gdx `Input.Keys` codes**), pumps
   `KeyInput.get().update()` per frame, and falls back to the fork `InputHandler` when no
   component has focus. The jME3 sibling is a `Jme3RootNode` whose `RawInputListener` (attached
   to `InputManager`) maps jME3 keycodes to BUI events the same way the gdx layer maps them
   now, and dispatches via `BRootNode.dispatchEvent`. The host harness deliberately wires no
   input (the window is static); this is the first thing a real client needs. (~1–2 days.)

2. **Type-token swap.** Mechanically swap `com.jme.renderer.Renderer` ->
   `com.jme3.renderer.RenderManager`, `com.jme.renderer.ColorRGBA` -> `com.jme3.math.ColorRGBA`,
   `com.jme.scene.Spatial`, `com.jme.image.Image`, etc. across `bui` + `app` + `client/shared`
   — including the **42 `renderComponent(Renderer)` overrides** in `client/shared`. The seam
   means BUI's *internals* are already off the fork; this swap is about the pass-through token
   types in signatures, and it is a big-but-mechanical diff (~2–3 agent-days). Once done,
   `BRootNode` stops extending the fork `Geometry` and `BImage` stops extending the fork `Quad`.

3. **`BGeomView` on jME3 ViewPorts.** The 3D-scene-inside-UI widget gets a dedicated jME3
   `ViewPort` rendered into the component's screen rect (jME3 supports multiple viewports
   natively — simpler than the fork's camera/`unsetOrtho` dance). The backend's `clearZBuffer`
   becomes that viewport's own depth clear. (~1 day.)

4. **Texture pool retype.** `BImage.TexturePool` is implemented by client `BangUI` with a
   fork-typed (`TextureState`) signature. The jME3 backing currently uses no eager pool
   (`acquire`/`release` are no-ops); the client pool gets retyped at the final port.
   (~0.5 day.)

5. **Hardware cursor + window glue.** `BCursor` is pure LWJGL2 and leaks
   `org.lwjgl.input.Cursor` to the client; it moves to LWJGL3/GLFW (`GlfwCursor`) with the
   atomic LWJGL2->LWJGL3 cutover (Phase 5 step 5). The harness uses the default cursor.

6. **Real-frame integration.** Wire `beginFrame`/`endFrame` into the real jME3 app loop the
   same way `BuiProcessor` does here (a `SceneProcessor` on the GUI viewport, ortho-overlay
   camera), and drive `BRootNode.updateGeometricState`-equivalent per frame for tooltip/timer
   logic. The harness's `BuiProcessor` + ortho-overlay setup is a working template.

The long pole remains the per-screen visual regression pass (every real screen embeds avatars
and models), which needs steps 4–6 of Phase 5 to exist first.
