//
// BUI - a user interface library for the JME 3D engine
// Copyright (C) 2005-2006, Michael Bayne, All Rights Reserved
// https://code.google.com/p/jme-bui/

package com.jmex.bui.backend;

import java.nio.IntBuffer;

import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GLContext;

import com.jme.renderer.RenderContext;
import com.jme.renderer.Renderer;
import com.jme.scene.state.AlphaState;
import com.jme.scene.state.gdx.records.LineRecord;
import com.jme.system.DisplaySystem;
import com.jme.util.geom.BufferUtils;

import com.jmex.bui.BImage;
import com.jmex.bui.util.Rectangle;

/**
 * The original BUI render implementation on the vendored jME fork (LWJGL2 immediate mode
 * GL plus the fork's render state records). This is the default backend; behavior is
 * identical to BUI before the backend seam was introduced.
 */
public class JmeRenderBackend implements BRenderBackend
{
    public JmeRenderBackend ()
    {
        // create the standard UI blend state (formerly created in BImage's static
        // initializer); this requires a live GL context
        if (BImage.blendState == null) {
            AlphaState astate =
                DisplaySystem.getDisplaySystem().getRenderer().createAlphaState();
            astate.setBlendEnabled(true);
            astate.setSrcFunction(AlphaState.SB_SRC_ALPHA);
            astate.setDstFunction(AlphaState.DB_ONE_MINUS_SRC_ALPHA);
            astate.setEnabled(true);
            BImage.blendState = astate;
        }
        _supportsNonPowerOfTwo = GLContext.getCapabilities().GL_ARB_texture_non_power_of_two;
    }

    // documentation inherited from interface BRenderBackend
    public int getDisplayWidth ()
    {
        return DisplaySystem.getDisplaySystem().getWidth();
    }

    // documentation inherited from interface BRenderBackend
    public int getDisplayHeight ()
    {
        return DisplaySystem.getDisplaySystem().getHeight();
    }

    // documentation inherited from interface BRenderBackend
    public boolean isWindowActive ()
    {
        return Display.isActive();
    }

    // documentation inherited from interface BRenderBackend
    public void applyDefaultStates ()
    {
        RenderContext ctx = DisplaySystem.getDisplaySystem().getCurrentContext();
        for (int ii = 0; ii < Renderer.defaultStateList.length; ii++) {
            if (Renderer.defaultStateList[ii] != null &&
                Renderer.defaultStateList[ii] != ctx.getCurrentState(ii)) {
                Renderer.defaultStateList[ii].apply();
            }
        }
    }

    // documentation inherited from interface BRenderBackend
    public void applyBlendState ()
    {
        BImage.blendState.apply();
    }

    // documentation inherited from interface BRenderBackend
    public void translate (int x, int y)
    {
        GL11.glTranslatef(x, y, 0);
    }

    // documentation inherited from interface BRenderBackend
    public void fillRect (float x, float y, float width, float height,
                          float r, float g, float b, float a)
    {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + width, y);
        GL11.glVertex2f(x + width, y + height);
        GL11.glVertex2f(x, y + height);
        GL11.glEnd();
    }

    // documentation inherited from interface BRenderBackend
    public void drawRectOutline (float x, float y, float width, float height, float lineWidth,
                                 float r, float g, float b, float a)
    {
        RenderContext ctx = DisplaySystem.getDisplaySystem().getCurrentContext();
        ((LineRecord)ctx.getLineRecord()).applyLineWidth(lineWidth);
        float offset = lineWidth / 2f;
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(x + offset, y + offset);
        GL11.glVertex2f(x + width - offset, y + offset);
        GL11.glVertex2f(x + width - offset, y + height - offset);
        GL11.glVertex2f(x + offset, y + height - offset);
        GL11.glVertex2f(x + offset, y + offset);
        GL11.glEnd();
    }

    // documentation inherited from interface BRenderBackend
    public void drawLine (float x1, float y1, float x2, float y2,
                          float r, float g, float b, float a)
    {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(x1, y1);
        GL11.glVertex2f(x2, y2);
        GL11.glEnd();
    }

    // documentation inherited from interface BRenderBackend
    public boolean intersectScissorBox (Rectangle store, int x, int y, int width, int height)
    {
        boolean enabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        if (enabled) {
            GL11.glGetInteger(GL11.GL_SCISSOR_BOX, _bbuf);
            store.set(_bbuf.get(0), _bbuf.get(1), _bbuf.get(2), _bbuf.get(3));
            int x1 = Math.max(x, store.x), y1 = Math.max(y, store.y),
                x2 = Math.min(x + width, store.x + store.width),
                y2 = Math.min(y + height, store.y + store.height);
            GL11.glScissor(x, y, Math.max(0, x2 - x1), Math.max(0, y2 - y1));
        } else {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL11.glScissor(x, y, width, height);
        }
        return enabled;
    }

    // documentation inherited from interface BRenderBackend
    public void restoreScissorState (boolean enabled, Rectangle rect)
    {
        if (enabled) {
            GL11.glScissor(rect.x, rect.y, rect.width, rect.height);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
    }

    // documentation inherited from interface BRenderBackend
    public void clearZBuffer ()
    {
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
    }

    // documentation inherited from interface BRenderBackend
    public boolean supportsNonPowerOfTwo ()
    {
        return _supportsNonPowerOfTwo;
    }

    // documentation inherited from interface BRenderBackend
    public BImageBacking createImageBacking (BImage image)
    {
        return new JmeImageBacking(image);
    }

    protected boolean _supportsNonPowerOfTwo;

    protected static IntBuffer _bbuf = BufferUtils.createIntBuffer(16);
}
