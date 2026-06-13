//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui;

import java.awt.image.BufferedImage;

/**
 * Contains a cursor image and its hotspot.
 *
 * <p> <b>jME3 cutover note (Phase 1/3):</b> the original implementation built and installed an
 * LWJGL2 {@code org.lwjgl.input.Cursor} directly via {@code Mouse.setNativeCursor}. The
 * shipped client constructs cursors from a {@link BufferedImage} + hotspot, so that is the API
 * kept here; the native hardware-cursor install moves to LWJGL3/GLFW at the atomic host
 * cutover (Phase 3, see {@code docs/jme3-bui-host.md} item #5). {@link #show} is a no-op until
 * that path lands.
 */
public class BCursor
{
    public BCursor (BufferedImage image, int hx, int hy)
    {
        setCursor(image, hx, hy);
    }

    /**
     * Configures this cursor's image and hotspot.
     */
    public void setCursor (BufferedImage image, int hx, int hy)
    {
        _image = image;
        _hx = hx;
        _hy = hy;
    }

    /**
     * Returns the cursor image.
     */
    public BufferedImage getImage ()
    {
        return _image;
    }

    /**
     * Returns the x coordinate of the cursor hotspot.
     */
    public int getHotspotX ()
    {
        return _hx;
    }

    /**
     * Returns the y coordinate of the cursor hotspot.
     */
    public int getHotspotY ()
    {
        return _hy;
    }

    /**
     * Display this cursor. No-op until the Phase-3 GLFW cursor path is installed.
     */
    public void show ()
    {
        // The native hardware cursor install moves to LWJGL3/GLFW at the host cutover.
    }

    protected BufferedImage _image;
    protected int _hx, _hy;
}
