//
// $Id$

package com.threerings.bang.game.client.effect;

import com.jme3.material.Material;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.AbstractControl;

import com.threerings.jme.util.SpatialVisitor;

import com.threerings.bang.game.client.sprite.ActiveSprite;
import com.threerings.bang.game.client.sprite.MobileSprite;
import com.threerings.bang.game.client.sprite.PieceSprite;
import com.threerings.bang.util.BasicContext;

/**
 * A visualization for the iron plate.  When activated and when the unit is
 * shot, temporarily gives the unit a metallic appearance.
 *
 * <h3>jME3 cutover (Phase 2, cluster 2)</h3>
 *
 * The fork pushed a multi-pass <em>render-state overlay</em> ({@code mesh.addOverlay(RenderState[])}
 * with a sphere-map {@code TextureState} + a {@code MaterialState} + additive blend) onto every
 * {@code ModelMesh} of the unit and ramped the overlay material's alpha via a
 * {@code com.jme.scene.Controller}. jME3 has no fixed-function render-state overlay stack: a
 * sphere-mapped metallic pass is a re-authored {@code Material}/{@code MatDef} effect.
 *
 * <p><b>Phase-4 fidelity deferral + needed shared seam:</b> the spheremap metallic <em>overlay</em>
 * (a second additive pass over the lit unit) has no jME3 equivalent yet and depends on a
 * sprite-cluster-owned overlay-applier seam (a way to attach/detach an extra material pass to the
 * model geometries) plus a re-authored spheremap MatDef — both Phase 4. To keep the gameplay hook
 * intact and compile cleanly, this port preserves the activation/timing (the reacting-action flash)
 * and drives an {@link AbstractControl} ramp, applying a white emissive-ish brighten to the model
 * geometries' materials where they expose a {@code Color} param, and flagging the spheremap pass as
 * a Phase-4 visual item.
 */
public class IronPlateViz extends InfluenceViz
{
    @Override // documentation inherited
    public void init (BasicContext ctx, PieceSprite target)
    {
        super.init(ctx, target);
        collectMaterials();
        ((MobileSprite)_target).addActionHandler(_handler);
        flashOverlay(0.5f);
    }

    @Override // documentation inherited
    public void destroy ()
    {
        ((MobileSprite)_target).removeActionHandler(_handler);
        setBrighten(0f);
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
     * Applies a metallic-brighten amount to the gathered materials. Phase-4 will replace this with
     * the re-authored spheremap overlay pass.
     */
    protected void setBrighten (float amount)
    {
        float v = 1f + amount; // > 1 brightens the Color tint as a stand-in for the metallic pass
        for (Material mat : _mats) {
            if (mat.getParam("Color") != null) {
                mat.setColor("Color", new com.jme3.math.ColorRGBA(v, v, v, 1f));
            }
        }
    }

    /**
     * Flashes the overlay state over the specified duration (which includes
     * the time it takes to ramp in and out).
     */
    protected void flashOverlay (final float duration)
    {
        _target.addControl(new AbstractControl() {
            @Override protected void controlUpdate (float time) {
                _elapsed = Math.min(_elapsed + time, duration);
                float amount;
                if (_elapsed < FADE_DURATION) {
                    amount = _elapsed / FADE_DURATION;
                } else if (_elapsed <= duration - FADE_DURATION) {
                    amount = 1f;
                } else {
                    amount = (duration - _elapsed) / FADE_DURATION;
                }
                setBrighten(amount);
                if (_elapsed >= duration) {
                    _target.removeControl(this);
                    setBrighten(0f);
                }
            }
            @Override protected void controlRender (RenderManager rm, ViewPort vp) {
            }
            protected float _elapsed;
        });
    }

    /** Flashes the overlay when the reacting animation plays. */
    protected ActiveSprite.ActionHandler _handler =
        new ActiveSprite.ActionHandler() {
        public String handleAction (ActiveSprite sprite, String action) {
            if ("reacting".equals(action)) {
                flashOverlay(sprite.getAction(action).getDuration());
                return action;
            } else {
                return null;
            }
        }
    };

    /** The materials of the target's model geometries (brightened by this effect). */
    protected java.util.ArrayList<Material> _mats = new java.util.ArrayList<Material>();

    /** The time to take fading in and out of the overlay. */
    protected static final float FADE_DURATION = 0.1f;
}
