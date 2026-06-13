//
// Phase 5 step 3 proof harness -- ISOLATED jME3 host for BUI's jME3 render backend.
//

package com.threerings.bang.tools.jme3host;

import com.jmex.bui.BComponent;
import com.jmex.bui.BRootNode;
import com.jmex.bui.BWindow;

/**
 * A concrete {@link BRootNode} for the jME3 host harness.
 *
 * <p> {@link BRootNode} is abstract and (as a Phase-5 documented exception) still
 * {@code extends com.jme.scene.Geometry} -- a fork type that is inert when the fork renderer
 * never initializes. We deliberately do NOT use the fork's {@code onDraw}/{@code draw}
 * (ortho-queue) path here; instead {@link #renderWindows} walks the protected window list and
 * drives each window's {@link BWindow#render} through whatever {@code BackendProvider} backend
 * is installed (here, {@code Jme3RenderBackend}). The {@code com.jme.renderer.Renderer}
 * argument that {@code render} threads through is a pass-through token only -- BUI's actual
 * drawing is routed through the backend -- so we pass {@code null}.
 *
 * <p> Input is NOT wired: {@code PolledRootNode} is the fork/LWJGL2 input glue and the jME3
 * sibling ({@code RawInputListener} -> BUI events) is future work (see docs/jme3-bui-host.md).
 * This harness only proves the render path.
 */
public class BuiRootNode extends BRootNode
{
    /** Renders every registered window, back-to-front, through the installed backend. The
     * host calls this on the render thread, bracketed by the backend's beginFrame/endFrame. */
    public void renderWindows ()
    {
        for (int ii = 0, ll = _windows.size(); ii < ll; ii++) {
            BWindow win = _windows.get(ii);
            try {
                // null is intentional: the jME3 backend ignores BUI's Renderer argument
                // (it renders through its own RenderManager). This harness therefore only
                // supports renderer-independent widgets -- a BGeomView, which dereferences
                // the Renderer (getCamera()/draw()), would NPE here. The real client UI gets
                // a proper jME3-backed BGeomView at the cutover (see docs/jme3-bui-host.md).
                win.render(null);
            } catch (Throwable t) {
                System.err.println("Window failed in render(): " + win);
                t.printStackTrace();
            }
        }
    }

    @Override // from BRootNode
    public long getTickStamp ()
    {
        return System.currentTimeMillis();
    }

    @Override // from BRootNode
    public void rootInvalidated (BComponent root)
    {
        // The harness validates windows explicitly after building the scene; no deferred
        // revalidation queue is needed for a static demo.
        root.validate();
    }
}
