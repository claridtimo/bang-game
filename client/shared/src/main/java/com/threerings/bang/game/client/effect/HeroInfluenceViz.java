//
// $Id$

package com.threerings.bang.game.client.effect;

import com.jme3.material.Material;
import com.jme3.math.Vector3f;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import com.jme3.scene.control.BillboardControl;

import com.threerings.jme.util.SpatialVisitor;

import com.threerings.bang.client.BangUI;

import com.threerings.bang.util.BasicContext;
import com.threerings.bang.util.RenderUtil;

import com.threerings.bang.game.client.sprite.PieceSprite;

import static com.threerings.bang.client.BangMetrics.*;

/**
 * An influence visualization that shows a floating number.
 *
 * <h3>jME3 cutover (Phase 2, cluster 2)</h3>
 *
 * Two fork mechanisms are ported here:
 * <ul>
 *   <li><b>The floating level count</b> was a fork {@code Quad} + {@code TextureState} (rendered
 *   text) hung on a {@code BillboardNode}. jME3: a text {@link Geometry} from
 *   {@link RenderUtil#createTextQuad} attached to a {@link Node} carrying a {@link BillboardControl}.
 *   </li>
 *   <li><b>The level-up colour overlay</b> was a sphere-map {@code TextureState} + {@code
 *   MaterialState} pushed onto every model {@code ModelMesh} via {@code addOverlay(RenderState[])}
 *   and ramped each frame. jME3 has no render-state overlay stack.</li>
 * </ul>
 *
 * <p><b>Phase-4 fidelity deferral + needed shared seam:</b> the spheremap player-colour <em>overlay
 * pass</em> over the lit unit has no jME3 equivalent yet — it needs a sprite-cluster-owned
 * overlay-applier seam (attach/detach an extra material pass on the model geometries) plus a
 * re-authored spheremap MatDef (Phase 4). This port keeps the level scaling + the floating count and
 * drives an {@link AbstractControl} that tints the model geometries' {@code Color}/{@code Diffuse}
 * material params toward the player colour as a stand-in, flagging the spheremap pass for Phase 4.
 */
public class HeroInfluenceViz extends InfluenceViz
{
    public HeroInfluenceViz (int level)
    {
        _level = level;
    }

    @Override // documentation inherited
    public void init (BasicContext ctx, PieceSprite target)
    {
        super.init(ctx, target);
        _owner = target.getPiece().owner;

        collectMaterials();

        // attach the floating level count on a screen-facing billboard
        _billboard = new Node("hero_influence");
        _billboard.addControl(new BillboardControl());
        _billboard.setLocalTranslation(new Vector3f(0, 0, target.getHeight() + 0.5f * TILE_SIZE));
        target.attachChild(_billboard);

        setLevel(_level);
    }

    /** Gathers the materials of the target's model geometries. */
    protected void collectMaterials ()
    {
        if (_target.getModelNode() == null) {
            return;
        }
        new SpatialVisitor<Geometry>(Geometry.class) {
            protected void visit (Geometry geom) {
                if (geom.getMaterial() != null) {
                    _mats.add(geom.getMaterial());
                }
            }
        }.traverse(_target.getModelNode());
    }

    /**
     * Update the level number.
     */
    public void setLevel (int level)
    {
        _level = level;

        // (re)build the floating count text geometry
        if (_count != null) {
            _billboard.detachChild(_count);
        }
        _count = RenderUtil.createTextQuad(
                _ctx, BangUI.COUNTER_FONT, getJPieceColor(_owner), getDarkerPieceColor(_owner),
                String.valueOf(_level));
        _billboard.attachChild(_count);

        float scale = 1f + _level * 0.05f;
        _target.setLocalScale(new Vector3f(scale, scale, scale));

        // apply the level-up tint toward the player colour
        applyTint(getAlpha());
    }

    /**
     * Tints the gathered model materials toward the player colour by the supplied amount.
     */
    protected void applyTint (float amount)
    {
        ColorRGBA pc = getJPieceColor(_owner);
        ColorRGBA c = new ColorRGBA();
        c.interpolateLocal(new ColorRGBA(ColorRGBA.White), pc, Math.min(1f, amount));
        for (Material mat : _mats) {
            if (mat.getParam("Color") != null) {
                mat.setColor("Color", c.clone());
            } else if (mat.getParam("Diffuse") != null) {
                mat.setColor("Diffuse", c.clone());
            }
        }
    }

    @Override // documentation inherited
    public void destroy ()
    {
        if (_target != null) {
            _target.detachChild(_billboard);
        }

        // fade out the colour change before removing
        final float duration = 1f;
        _target.addControl(new AbstractControl() {
            @Override protected void controlUpdate (float time) {
                _elapsed = Math.min(_elapsed + time, duration);
                float amount = getAlpha() * (duration - _elapsed) / duration;
                applyTint(amount);
                if (_elapsed >= duration) {
                    _target.removeControl(this);
                    applyTint(0f);
                }
            }
            @Override protected void controlRender (RenderManager rm, ViewPort vp) {
            }
            protected float _elapsed;
        });
    }

    /**
     * Calculates the alpha value for this hero level.
     */
    protected float getAlpha ()
    {
        if (_level < 5) {
            return _level / 5f * .3f;
        }
        return .3f + (_level - 5) / 10f;
    }

    protected int _level, _owner;

    // our floating level indicator
    protected Node _billboard;
    protected Geometry _count;

    /** The materials of the target's model geometries (tinted by this effect). */
    protected java.util.ArrayList<Material> _mats = new java.util.ArrayList<Material>();
}
