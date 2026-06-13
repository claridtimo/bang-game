//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

import com.jme.image.Image;

/**
 * The engine-specific guts of a {@link com.jmex.bui.BImage}: texture creation, texture
 * coordinate management and quad rendering.
 *
 * <p> Note: {@link com.jme.image.Image} appears here because it is a plain, GL-free pixel
 * container (format constant, dimensions and a byte buffer) that is also part of BImage's
 * frozen public constructor API; non-fork backends treat it as a dumb data holder.
 */
public interface BImageBacking
{
    /** Configures the image (pixel) data to be used. */
    void setImage (Image image);

    /** Configures whether the image is rendered with the standard UI blend state. */
    void setTransparent (boolean transparent);

    /** Configures the texture coordinates to address the specified subimage (in pixels of
     * the source image). */
    void setTextureCoords (int sx, int sy, int swidth, int sheight);

    /**
     * Renders the currently configured texture region at the specified coordinates, scaled
     * to the specified size.
     *
     * @param renderer the engine renderer handed to BUI's render traversal (the fork's
     * {@code com.jme.renderer.Renderer}); typed loosely so the interface stays engine-neutral.
     */
    void render (Object renderer, int tx, int ty, int twidth, int theight, float alpha);

    /** Called when the image becomes referenced: load the texture into graphics memory. */
    void acquire ();

    /** Called when the image is no longer referenced: release the texture. */
    void release ();
}
