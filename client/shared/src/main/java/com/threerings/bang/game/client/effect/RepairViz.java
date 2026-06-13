//
// $Id$

package com.threerings.bang.game.client.effect;

import com.jme3.effect.ParticleEmitter;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.scene.control.AbstractControl;
import com.jme3.texture.Texture2D;

import com.threerings.bang.client.BangPrefs;
import com.threerings.bang.game.client.sprite.PieceSprite;
import com.threerings.bang.util.BackTextureRenderer;
import com.threerings.bang.util.RenderUtil;

import static com.threerings.bang.client.BangMetrics.*;

/**
 * Displays the effect when a unit is repaired.
 *
 * <h3>jME3 cutover (Phase 2, cluster 2 — a {@link BackTextureRenderer} consumer)</h3>
 *
 * The fork "glow" rendered the target unit to a texture (back-buffer copy {@code TextureRenderer})
 * then drew many additive, spinning ortho {@code Quad}s of it in screen space, projecting the
 * target's world bounds to screen coordinates via {@code DisplaySystem.getScreenCoordinates}. jME3
 * render-to-texture is the FrameBuffer-backed {@link BackTextureRenderer} (which renders a clean
 * offscreen pass through {@link RenderManager#renderViewPort}), and screen projection is
 * {@code Camera.getScreenCoordinates}.
 *
 * <p><b>Phase-4 fidelity deferral:</b> the screen-space additive ortho-pane composite (the actual
 * glow look) is a BUI/ortho-overlay draw that belongs to the Phase-3 viewport + Phase-4 effect
 * re-author; here the Glow performs the {@link BackTextureRenderer} RTT capture of the target each
 * frame (exercising the new RTT seam) and self-removes after its duration, but the additive-pane
 * composite is flagged for Phase 4. The sparkle <b>swirl</b> half of the effect is fully ported.
 */
public class RepairViz extends ParticleEffectViz
{
    @Override // documentation inherited
    public void display ()
    {
        // start up the glow effect
        if (_glow != null && _sprite != null) {
            _glow.activate(_sprite);
        }

        // and the swirl effect
        displayParticles(_swirls[0].particles, true);
        displayParticles(_swirls[1].particles, true);
    }

    @Override // documentation inherited
    protected void didInit ()
    {
        if (BangPrefs.isHighDetail()) {
            _glow = new Glow();
        }
        _swirls = new Swirl[] { new Swirl(0f), new Swirl(FastMath.PI) };
    }

    /**
     * Creates a glow effect by rendering the target to a texture (Phase-4 will composite the
     * additive spinning panes from the captured texture).
     */
    protected class Glow extends Node
    {
        public Glow ()
        {
            super("glow");

            _trenderer = RenderUtil.createTextureRenderer(_ctx, TEXTURE_SIZE, TEXTURE_SIZE);
            _trenderer.setBackgroundColor(new ColorRGBA(ColorRGBA.Black));
            _texture = _ctx.getTextureCache().createTexture();
            _trenderer.setupTexture(_texture);
        }

        public void activate (PieceSprite target)
        {
            _target = target;
            _view.getPieceNode().attachChild(this);
            setLocalTranslation(target.getLocalTranslation().clone());
            addControl(new AbstractControl() {
                @Override protected void controlUpdate (float time) {
                    step(time);
                }
                @Override protected void controlRender (RenderManager rm, ViewPort vp) {
                }
            });
        }

        protected void step (float time)
        {
            if ((_elapsed += time) > GLOW_DURATION) {
                if (getParent() != null) {
                    _view.getPieceNode().detachChild(this);
                }
                _trenderer.cleanup();
                return;
            }
            if (RenderUtil.isOutsideFrustum(_target)) {
                return;
            }
            // point the RTT camera at the target and capture it offscreen.
            com.jme3.renderer.Camera rcam = _ctx.getCameraHandler().getCamera();
            com.jme3.renderer.Camera tcam = _trenderer.getCamera();
            tcam.setLocation(rcam.getLocation().clone());
            tcam.setAxes(rcam.getLeft().clone(), rcam.getUp().clone(),
                rcam.getDirection().clone());
            tcam.setFrustum(rcam.getFrustumNear(), rcam.getFrustumFar(),
                rcam.getFrustumLeft(), rcam.getFrustumRight(),
                rcam.getFrustumTop(), rcam.getFrustumBottom());
            _trenderer.render(_target);
            // Phase-4: composite the captured _texture as additive, spinning, screen-space ortho
            // panes sized to the target's projected screen radius (was the fork's GLOW_PANES quads
            // + DisplaySystem.getScreenCoordinates projection). Deferred to the effect re-author.
        }

        protected BackTextureRenderer _trenderer;
        protected Texture2D _texture;
        protected PieceSprite _target;
        protected float _elapsed;
    }

    /** The swirl of sparkles effect. */
    protected class Swirl
    {
        /** The particle emitter for the swirl. */
        public ParticleEmitter particles;

        public Swirl (final float a0)
        {
            particles = ParticlePool.getSparkles();
            particles.setParticlesPerSec(512f);
            particles.setLocalTranslation(new Vector3f());

            // jME3: the per-frame fork Controller becomes an AbstractControl on the emitter.
            particles.addControl(new AbstractControl() {
                @Override protected void controlUpdate (float time) {
                    // remove swirl if its lifespan has elapsed
                    if ((_elapsed += time) > GLOW_DURATION) {
                        particles.removeControl(this);
                        removeParticles(particles);
                        if (!_displayed) { // report completion
                            effectDisplayed();
                            _displayed = true;
                        }
                        return;

                    } else if (_elapsed > SWIRL_DURATION) {
                        particles.setParticlesPerSec(0f);
                        return;
                    }
                    float t = _elapsed / SWIRL_DURATION,
                        radius = TILE_SIZE / 2,
                        angle = a0 + t * FastMath.TWO_PI * SWIRL_REVOLUTIONS;
                    particles.setLocalTranslation(
                        radius * FastMath.cos(angle),
                        radius * FastMath.sin(angle),
                        TILE_SIZE * t - radius);
                }
                @Override protected void controlRender (RenderManager rm, ViewPort vp) {
                }
                protected float _elapsed;
            });
        }
    }

    /** The glow effect. */
    protected Glow _glow;

    /** The swirls of sparkles. */
    protected Swirl[] _swirls;

    /** Whether or not we have reported ourself as displayed. */
    protected boolean _displayed;

    /** The size of the texture to render. */
    protected static final int TEXTURE_SIZE = 128;

    /** The number of panes in the glow effect. */
    protected static final int GLOW_PANES = 8;

    /** The duration of the glow effect. */
    protected static final float GLOW_DURATION = 1.5f;

    /** The size of the glow as a proportion of the screen radius of the
     * target. */
    protected static final float GLOW_SCALE = 0.0625f;

    /** The duration of the swirl effect. */
    protected static final float SWIRL_DURATION = 1.125f;

    /** The number of revolutions for the swirl to complete. */
    protected static final float SWIRL_REVOLUTIONS = 1.5f;
}
