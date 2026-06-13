//
// $Id$
//
// Nenya library - tools for developing networked games
// Copyright (C) 2002-2010 Three Rings Design, Inc., All Rights Reserved
// http://code.google.com/p/nenya/
//
// This library is free software; you can redistribute it and/or modify it
// under the terms of the GNU Lesser General Public License as published
// by the Free Software Foundation; either version 2.1 of the License, or
// (at your option) any later version.
//
// This library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
// Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public
// License along with this library; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307 USA

package com.threerings.jme;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.samskivert.util.RunQueue;
import com.samskivert.util.StringUtil;

import com.jme3.asset.AssetManager;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Node;

import com.jmex.bui.BRootNode;

import com.threerings.jme.camera.CameraHandler;
import com.threerings.jme.camera.GodViewHandler;

/**
 * Defines a basic application framework providing integration with the Presents networking system
 * and a jME3 scene-graph host.
 *
 * <p>jME3 cutover (Phase 1): this is the host loop, the primary {@code app} rebuild target
 * (§3.1 / risk #8 of the migration map). It was the gdx {@code ApplicationListener} driving the
 * fork {@code DisplaySystem}/{@code Renderer}: gdx owned the LWJGL2 window+GL context+main loop,
 * and the fork rendered into it (engine-notes "three layers sharing one GL context"). Under jME3
 * the engine owns its own LWJGL3 context and main loop, so the gdx host and the fork render
 * driving go away entirely. The full host — a {@code com.jme3.app.SimpleApplication} (or custom
 * {@code Application}) on the LWJGL3 context, the {@code RawInputListener} → BUI input path, and
 * the {@code Jme3RenderBackend} install — is the <b>Phase-3 atomic flip</b>; that flip cannot
 * land until {@code client/shared} compiles (Phase 2). So Phase 1 retypes this class onto jME3
 * types and preserves its {@link JmeContext} + {@link RunQueue} seam, leaving the live context
 * creation / per-frame render loop to the Phase-3 {@code SimpleApplication} subclass.
 *
 * <p>The fork's 1×1-init reinit dance (engine-notes §threading: the editor's {@code LwjglCanvas}
 * created the GL display before AWT layout, forcing {@code JmeApp.resize()} to {@code reinit} the
 * renderer + refresh the frustum) goes away — jME3 owns the context and sizes it correctly.
 */
public class JmeApp
    implements RunQueue, JmeContext
{
    /**
     * Returns a context implementation that provides access to all the necessary bits.
     */
    public JmeContext getContext ()
    {
        return this;
    }

    /**
     * Installs the jME3 services this host exposes. Phase 3's {@code SimpleApplication} host
     * calls this from {@code simpleInitApp()} with the engine-owned services; until then it lets
     * tools/tests build a context without a live GL context.
     */
    public void init (AssetManager assetManager, RenderManager renderManager, Camera camera)
    {
        _assetManager = assetManager;
        _renderManager = renderManager;
        _camera = camera;

        initRoot();
        initInput();
        initInterface();
    }

    /**
     * (Re)applies our standard perspective frustum to the camera for the given viewport size.
     */
    public void setPerspectiveFrustum (int width, int height)
    {
        if (_camera != null && width > 0 && height > 0) {
            _camera.setFrustumPerspective(45.0f, width / (float)height, 1, 10000);
        }
    }

    /**
     * Returns the frames per second averaged over recent frames. Populated by the Phase-3 host.
     */
    public float getRecentFrameRate ()
    {
        return _frameRate;
    }

    /**
     * Instructs the application to stop the main loop, cleanup and exit. The live teardown is
     * wired by the Phase-3 host; here we just flip the running flag.
     */
    public void stop ()
    {
        _finished = true;
    }

    // from interface RunQueue
    public void postRunnable (Runnable r)
    {
        // queued here in Phase 1; the Phase-3 host drains this on the jME3 render thread (jME3's
        // Application.enqueue), replacing gdx's postRunnable.
        _runnables.add(r);
    }

    /**
     * Drains and runs any queued runnables. Called by the Phase-3 host once per frame on the
     * render thread.
     */
    public void executeRunnables ()
    {
        Runnable r;
        while ((r = _runnables.poll()) != null) {
            r.run();
        }
    }

    // from interface RunQueue
    public boolean isDispatchThread ()
    {
        return Thread.currentThread() == _dispatchThread;
    }

    // from interface RunQueue
    public boolean isRunning ()
    {
        return !_finished;
    }

    /**
     * Sets up a main input controller to handle the camera and deal with global user input.
     */
    protected void initInput ()
    {
        _camhand = createCameraHandler(_camera);
        _inputHandler = createInputHandler(_camhand);
        // the jME3 InputManager mappings (GodViewHandler.registerWith) are installed by the
        // Phase-3 host once it owns the input manager. The handler object exists now so
        // camera-control logic (enable/disable, round setup) works pre-host.
    }

    /**
     * Creates the camera handler which provides various camera manipulation functionality.
     */
    protected CameraHandler createCameraHandler (Camera camera)
    {
        return new CameraHandler(camera);
    }

    /**
     * Creates the input handler (replacing the fork's polled {@code InputHandler}). Subclasses
     * override to install a game- or editor-specific handler.
     */
    protected GodViewHandler createInputHandler (CameraHandler camhand)
    {
        return new GodViewHandler(camhand);
    }

    /**
     * Creates our root node and the geometry sub-node.
     */
    protected void initRoot ()
    {
        _root = new Node("Root");

        // set up a node for our geometry. depth/light/render-state setup that the fork did with
        // ZBufferState/LightState render-state objects is now per-Geometry Material state +
        // Spatial.addLight, applied by the board renderer in Phase 2/4.
        _geom = new Node("Geometry");
        _root.attachChild(_geom);
    }

    /**
     * Initializes our user interface bits.
     */
    protected void initInterface ()
    {
        // set up a node for our interface
        _iface = new Node("Interface");
        _root.attachChild(_iface);

        // create our root node
        _rnode = createRootNode();
    }

    /**
     * Allows a customized BUI root node to be created. The fork {@code PolledRootNode} (LWJGL2/
     * gdx input glue) was deleted in the bui migration; the jME3 {@code Jme3RootNode}
     * (RawInputListener-fed) is installed at the Phase-3 host flip, so this returns null until
     * a subclass overrides it.
     */
    protected BRootNode createRootNode ()
    {
        return null;
    }

    /**
     * Prepends the necessary bits onto the supplied path to properly locate it in our
     * configuration directory.
     */
    protected String getConfigPath (String file)
    {
        String cfgdir = ".narya";
        String home = System.getProperty("user.home");
        if (!StringUtil.isBlank(home)) {
            cfgdir = home + File.separator + cfgdir;
        }
        // create the configuration directory if it does not already exist
        File dir = new File(cfgdir);
        if (!dir.exists()) {
            dir.mkdir();
        }
        return cfgdir + File.separator + file;
    }

    // from interface JmeContext
    public AssetManager getAssetManager () {
        return _assetManager;
    }

    // from interface JmeContext
    public RenderManager getRenderManager () {
        return _renderManager;
    }

    // from interface JmeContext
    public Camera getCamera () {
        return _camera;
    }

    // from interface JmeContext
    public CameraHandler getCameraHandler () {
        return _camhand;
    }

    // from interface JmeContext
    public GodViewHandler getInputHandler () {
        return _inputHandler;
    }

    // from interface JmeContext
    public Node getGeometry () {
        return _geom;
    }

    // from interface JmeContext
    public Node getInterface () {
        return _iface;
    }

    // from interface JmeContext
    public BRootNode getRootNode () {
        return _rnode;
    }

    protected Thread _dispatchThread;
    protected float _frameTime;
    protected float _frameRate;

    protected AssetManager _assetManager;
    protected RenderManager _renderManager;
    protected Camera _camera;
    protected CameraHandler _camhand;
    protected GodViewHandler _inputHandler;

    protected BRootNode _rnode;

    protected boolean _updateEnabled = true, _renderEnabled = true;
    protected boolean _finished;

    protected Node _root, _geom, _iface;

    /** Runnables posted from other threads, drained on the render thread by the Phase-3 host. */
    protected final ConcurrentLinkedQueue<Runnable> _runnables = new ConcurrentLinkedQueue<>();
}
