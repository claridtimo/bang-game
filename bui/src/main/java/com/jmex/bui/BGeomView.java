//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui;

import com.jme3.renderer.RenderManager;
import com.jme3.scene.Spatial;

import com.jmex.bui.util.Rectangle;

/**
 * Displays 3D geometry (a {@link Spatial}) inside a normal user interface.
 *
 * <p> <b>jME3 cutover note (Phase 1/3):</b> the original widget drove its own fork
 * {@code Camera} and called the fork renderer's {@code unsetOrtho}/{@code draw}/{@code setOrtho}
 * dance to render a 3D spatial into the component's screen rect. jME3 has no immediate-mode
 * draw hook; the equivalent is a dedicated jME3 {@code ViewPort} whose camera viewport is set
 * to the component's screen rect, rendered each frame and depth-cleared by the host (see
 * {@code docs/jme3-bui-host.md} item #3). That viewport wiring is installed at the atomic
 * host cutover (Phase 3). For now this holds the spatial and updates it per frame; the actual
 * 3D render is a no-op until the Phase-3 viewport path lands.
 */
public class BGeomView extends BComponent
{
    /**
     * Creates a view with no configured geometry. Geometry can be set later with {@link
     * #setGeometry}.
     */
    public BGeomView ()
    {
        this(null);
    }

    /**
     * Creates a view with the specified {@link Spatial} to be rendered.
     */
    public BGeomView (Spatial geom)
    {
        _geom = geom;
    }

    /**
     * Configures the spatial to be rendered by this view.
     */
    public void setGeometry (Spatial geom)
    {
        _geom = geom;
    }

    /**
     * Returns the geometry rendered by this view.
     */
    public Spatial getGeometry ()
    {
        return _geom;
    }

    /**
     * Called every frame (while we're added to the view hierarchy) by the {@link BRootNode}.
     */
    public void update (float frameTime)
    {
        if (_geom != null) {
            // jME3 splits the fork's single updateGeometricState(time, initiator) into a
            // logical (time-stepped controls/anim) update and a geometric (transform/bound)
            // update.
            _geom.updateLogicalState(frameTime);
            _geom.updateGeometricState();
        }
    }

    // documentation inherited
    protected void wasAdded ()
    {
        super.wasAdded();
        _root = getWindow().getRootNode();
        _root.registerGeomView(this);
    }

    // documentation inherited
    protected void wasRemoved ()
    {
        super.wasRemoved();
        _root.unregisterGeomView(this);
        _root = null;
    }

    // documentation inherited
    protected void renderComponent (RenderManager renderer)
    {
        super.renderComponent(renderer);
        // The 3D render is performed by a dedicated jME3 ViewPort installed at the Phase-3
        // host cutover (see class javadoc); nothing to draw inline here.
    }

    protected BRootNode _root;
    protected Spatial _geom;

    protected Rectangle _srect = new Rectangle();
}
