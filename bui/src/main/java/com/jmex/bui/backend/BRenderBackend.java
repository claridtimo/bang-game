//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

import com.jmex.bui.BImage;
import com.jmex.bui.util.Rectangle;

/**
 * Isolates every place where BUI touches the rendering engine behind an engine-neutral
 * interface, so that BUI's internals can be moved from the vendored jME fork to another
 * engine (jME3) without changing BUI's public API. See docs/jme3-bui-port.md.
 *
 * <p> All coordinates are in BUI's screen space (origin bottom-left, pixels), already offset
 * by any translations established via {@link #translate}.
 */
public interface BRenderBackend
{
    /** Returns the width of the display in pixels. */
    int getDisplayWidth ();

    /** Returns the height of the display in pixels. */
    int getDisplayHeight ();

    /** Returns true if the application window currently has focus. */
    boolean isWindowActive ();

    /** Applies the engine's default render states, clearing out any leftover state. */
    void applyDefaultStates ();

    /** Applies the standard user interface blend state (source alpha, one minus source
     * alpha) used when rendering transparent interface elements. */
    void applyBlendState ();

    /** Translates the rendering coordinate system by the supplied offsets. Callers undo a
     * translation by calling this again with negated offsets. */
    void translate (int x, int y);

    /** Fills a rectangle with a solid color. */
    void fillRect (float x, float y, float width, float height,
                   float r, float g, float b, float a);

    /** Draws a rectangle outline with the specified line width, inset so that the lines lie
     * entirely within the rectangle bounds. */
    void drawRectOutline (float x, float y, float width, float height, float lineWidth,
                          float r, float g, float b, float a);

    /** Draws a single one pixel wide line. */
    void drawLine (float x1, float y1, float x2, float y2,
                   float r, float g, float b, float a);

    /**
     * Activates scissoring and sets the scissor region to the intersection of the current
     * region (if any) and the specified rectangle. After rendering the scissored region, call
     * {@link #restoreScissorState} to restore the previous state.
     *
     * @param store a rectangle to hold the previous scissor region for later restoration.
     * @return true if scissoring was already enabled, false if it was not.
     */
    boolean intersectScissorBox (Rectangle store, int x, int y, int width, int height);

    /**
     * Restores the previous scissor state after a call to {@link #intersectScissorBox}.
     *
     * @param enabled the value returned by {@link #intersectScissorBox}.
     * @param rect the scissor box to restore.
     */
    void restoreScissorState (boolean enabled, Rectangle rect);

    /** Clears the depth buffer (honoring any active scissor region). Used when embedding 3D
     * geometry inside the interface. */
    void clearZBuffer ();

    /** Returns true if the engine supports non-power-of-two texture dimensions. */
    boolean supportsNonPowerOfTwo ();

    /** Creates the engine-specific backing for the supplied image. */
    BImageBacking createImageBacking (BImage image);
}
