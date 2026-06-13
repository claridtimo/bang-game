//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

import java.nio.ByteBuffer;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.scene.Geometry;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.shape.Quad;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;

import com.jmex.bui.BImage;

/**
 * The {@link BImageBacking} implementation on jMonkeyEngine 3.
 *
 * <p> The fork {@link com.jme.image.Image} handed to {@link #setImage} is a plain pixel
 * container produced by {@link BImage} from an AWT raster: byte order is BGR
 * ({@code TYPE_3BYTE_BGR}) or ABGR ({@code TYPE_4BYTE_ABGR}), which map directly onto jME3's
 * {@link com.jme3.texture.Image.Format#BGR8 BGR8} / {@link com.jme3.texture.Image.Format#ABGR8
 * ABGR8} with no channel reshuffle. The pixels become a {@link Texture2D} on an
 * {@code Unshaded} material; rendering issues a textured {@link Quad} geometry through the
 * backend's render manager, honoring the translate and scissor stacks set up by
 * {@link Jme3RenderBackend}.
 */
public class Jme3ImageBacking implements BImageBacking
{
    public Jme3ImageBacking (Jme3RenderBackend backend, BImage image)
    {
        _backend = backend;
        _image = image;
        _material = new Material(backend.getAssetManager(),
                                 "Common/MatDefs/Misc/Unshaded.j3md");
        _material.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        _material.getAdditionalRenderState().setDepthTest(false);
        _material.getAdditionalRenderState().setDepthWrite(false);
        // The GUI ortho camera looks toward +Z and a jME3 Quad's front face (+Z normal) faces
        // away from it, so the default Back cull mode would discard every textured UI quad.
        _material.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
    }

    // documentation inherited from interface BImageBacking
    public void setImage (com.jme.image.Image image)
    {
        _twidth = image.getWidth();
        _theight = image.getHeight();

        com.jme3.texture.Image.Format format = (image.getType() == com.jme.image.Image.RGB888)
            ? com.jme3.texture.Image.Format.BGR8
            : com.jme3.texture.Image.Format.ABGR8;

        // copy the source pixels into a fresh direct buffer for jME3 to own
        ByteBuffer src = image.getData();
        src.rewind();
        ByteBuffer dst = BufferUtils.createByteBuffer(src.remaining());
        dst.put(src);
        dst.flip();

        com.jme3.texture.Image jimg = new com.jme3.texture.Image(
            format, _twidth, _theight, dst, com.jme3.texture.image.ColorSpace.sRGB);
        Texture2D texture = new Texture2D(jimg);
        texture.setMinFilter(Texture.MinFilter.BilinearNoMipMaps);
        texture.setMagFilter(Texture.MagFilter.Bilinear);
        _material.setTexture("ColorMap", texture);
    }

    // documentation inherited from interface BImageBacking
    public void setTransparent (boolean transparent)
    {
        _material.getAdditionalRenderState().setBlendMode(
            transparent ? RenderState.BlendMode.Alpha : RenderState.BlendMode.Off);
    }

    // documentation inherited from interface BImageBacking
    public void setTextureCoords (int sx, int sy, int swidth, int sheight)
    {
        _lx = sx / (float)_twidth;
        _ly = sy / (float)_theight;
        _ux = (sx + swidth) / (float)_twidth;
        _uy = (sy + sheight) / (float)_theight;
    }

    // documentation inherited from interface BImageBacking
    public void render (Object renderer, int tx, int ty, int twidth, int theight, float alpha)
    {
        if (_backend.getRenderManager() == null) {
            return;
        }
        Quad quad = new Quad(twidth, theight);
        // jME3's Quad lays out tex coords (0,0)-(1,1); rewrite to our sub-rect. Quad winds
        // bottom-left, bottom-right, top-right, top-left.
        VertexBuffer tc = quad.getBuffer(VertexBuffer.Type.TexCoord);
        BufferUtils.setInBuffer(new Vector2f(_lx, _ly), (java.nio.FloatBuffer)tc.getData(), 0);
        BufferUtils.setInBuffer(new Vector2f(_ux, _ly), (java.nio.FloatBuffer)tc.getData(), 1);
        BufferUtils.setInBuffer(new Vector2f(_ux, _uy), (java.nio.FloatBuffer)tc.getData(), 2);
        BufferUtils.setInBuffer(new Vector2f(_lx, _uy), (java.nio.FloatBuffer)tc.getData(), 3);
        tc.updateData(tc.getData());

        _material.setColor("Color", new ColorRGBA(1f, 1f, 1f, alpha));

        Geometry geom = new Geometry("bui-image", quad);
        geom.setMaterial(_material);
        geom.setLocalTranslation(_backend.getTranslateX() + tx,
                                 _backend.getTranslateY() + ty, 0);
        geom.updateGeometricState();
        _backend.getRenderManager().renderGeometry(geom);
    }

    // documentation inherited from interface BImageBacking
    public void acquire ()
    {
        // jME3 uploads the texture lazily on first render; no eager pooling here.
    }

    // documentation inherited from interface BImageBacking
    public void release ()
    {
        // The host's jME3 renderer reclaims GL textures; nothing to do per-image.
    }

    protected Jme3RenderBackend _backend;
    protected BImage _image;
    protected Material _material;
    protected int _twidth, _theight;
    protected float _lx, _ly, _ux = 1f, _uy = 1f;
}
