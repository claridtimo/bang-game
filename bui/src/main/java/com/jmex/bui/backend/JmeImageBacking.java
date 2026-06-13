//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

import java.nio.FloatBuffer;

import com.jme.image.Image;
import com.jme.image.Texture;
import com.jme.renderer.Renderer;
import com.jme.scene.state.RenderState;
import com.jme.scene.state.TextureState;
import com.jme.system.DisplaySystem;

import com.jmex.bui.BImage;

/**
 * The original {@link BImage} rendering guts on the vendored jME fork: the image's
 * {@code Quad} superclass is textured via a fork {@link TextureState} and drawn directly
 * through the fork renderer's ortho queue.
 */
public class JmeImageBacking implements BImageBacking
{
    public JmeImageBacking (BImage image)
    {
        _image = image;
        _tstate = DisplaySystem.getDisplaySystem().getRenderer().createTextureState();
    }

    // documentation inherited from interface BImageBacking
    public void setImage (Image image)
    {
        Texture texture = new Texture();
        texture.setImage(image);

        _twidth = image.getWidth();
        _theight = image.getHeight();

        texture.setFilter(Texture.FM_LINEAR);
        texture.setMipmapState(Texture.MM_LINEAR);
        _tstate.setTexture(texture);
        _tstate.setEnabled(true);
        _tstate.setCorrection(TextureState.CM_AFFINE);
        _image.setRenderState(_tstate);
        _image.updateRenderState();
    }

    // documentation inherited from interface BImageBacking
    public void setTransparent (boolean transparent)
    {
        if (transparent) {
            _image.setRenderState(BImage.blendState);
        } else {
            _image.clearRenderState(RenderState.RS_ALPHA);
        }
        _image.updateRenderState();
    }

    // documentation inherited from interface BImageBacking
    public void setTextureCoords (int sx, int sy, int swidth, int sheight)
    {
        float lx = sx / (float)_twidth;
        float ly = sy / (float)_theight;
        float ux = (sx+swidth) / (float)_twidth;
        float uy = (sy+sheight) / (float)_theight;

        FloatBuffer tcoords = _image.getTextureBuffer(0, 0);
        tcoords.clear();
        tcoords.put(lx).put(uy);
        tcoords.put(lx).put(ly);
        tcoords.put(ux).put(ly);
        tcoords.put(ux).put(uy);
        tcoords.flip();
    }

    // documentation inherited from interface BImageBacking
    public void render (Object renderer, int tx, int ty, int twidth, int theight, float alpha)
    {
        _image.resize(twidth, theight);
        _image.getLocalTranslation().x = tx + twidth/2f;
        _image.getLocalTranslation().y = ty + theight/2f;
        _image.updateGeometricState(0, true);

        _image.getBatch(0).getDefaultColor().a = alpha;
        _image.draw((Renderer)renderer);
    }

    // documentation inherited from interface BImageBacking
    public void acquire ()
    {
        if (_tstate.getNumberOfSetTextures() > 0) {
            BImage.getTexturePool().acquireTextures(_tstate);
        }
    }

    // documentation inherited from interface BImageBacking
    public void release ()
    {
        if (_tstate.getNumberOfSetTextures() > 0) {
            BImage.getTexturePool().releaseTextures(_tstate);
        }
    }

    protected BImage _image;
    protected TextureState _tstate;
    protected int _twidth, _theight;
}
