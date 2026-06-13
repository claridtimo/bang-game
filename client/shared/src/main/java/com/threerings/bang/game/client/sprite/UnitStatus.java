//
// $Id$

package com.threerings.bang.game.client.sprite;

import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Spatial.CullHint;
import com.jme3.texture.Texture.WrapMode;
import com.jme3.texture.Texture2D;

import com.jmex.bui.background.BBackground;

import com.threerings.bang.game.client.TerrainNode;
import com.threerings.bang.game.data.piece.Piece;
import com.threerings.bang.game.data.piece.Unit;
import com.threerings.bang.game.data.piece.Influence;

import com.threerings.bang.util.BasicContext;

import static com.threerings.bang.client.BangMetrics.*;

/**
 * A helper class to manage the composition of our unit status display.
 */
public class UnitStatus extends PieceStatus
{
    /**
     * Creates a unit status helper with the supplied unit sprite highlight
     * node. The status will be textured onto the highlight node (using a
     * {@link SharedMesh}) and will be textured onto a set of quads which will
     * be used to display our iconic unit status (which we make available as a
     * {@link BBackground}.
     */
    public UnitStatus (BasicContext ctx, TerrainNode.Highlight highlight)
    {
        super(ctx, highlight);

    }

    /**
     * Recomposites if necessary our status texture and updates the texture
     * state.
     */
    public void update (Piece piece, int ticksToMove,
                    UnitSprite.AdvanceOrder pendo, boolean selected, int pidx)
    {
        super.update(piece, selected);

        if (_ticksToMove != ticksToMove) {
            _ticksToMove = ticksToMove;

            // update our tick texture
            int tickidx = Math.max(0, 4-ticksToMove);
            setLayerTexture(2, _ticktexs[tickidx]);

            // update our outline texture
            setLayerTexture(0, (_ticksToMove > 0) ? _outtex : _routtex);
        }

        if (_pendo == null || _pendo != pendo) {
            _pendo = pendo;

            Texture2D otex;
            switch (_pendo) {
            case MOVE: otex = _movetex; break;
            case MOVE_SHOOT: otex = _shoottex; break;
            default: otex = null; break;
            }
            if (otex == null) {
                _info[3].setCullHint(CullHint.Always);
                _icon[3].setCullHint(CullHint.Always);
            } else {
                _info[3].setCullHint(CullHint.Dynamic);
                _icon[3].setCullHint(CullHint.Dynamic);
                setLayerTexture(3, otex);
            }
        }

        Unit unit = (Unit)piece;
        if (unit.getMainInfluence() == null || unit.getMainInfluence() != _influence) {
            _influence = unit.getMainInfluence();
            if (_influence == null ||
                    (_influence.hidden() && unit.owner != pidx)) {
                _info[4].setCullHint(CullHint.Always);
            } else {
                Texture2D tex = _ctx.getTextureCache().getTexture(
                    "influences/icons/" + _influence.getName() + ".png");
                tex.setWrap(WrapMode.Clamp);
                _info[4].setCullHint(CullHint.Dynamic);
                _infoMats[4].setTexture("ColorMap", tex);
            }
        }
        _icon[4].setCullHint(CullHint.Always);
    }

    @Override // documentation inherited
    public void rotateWithCamera (Quaternion camrot, Vector3f camtrans)
    {
        super.rotateWithCamera(camrot, camtrans);
        // jME3: the fork rotated/scaled the influence icon's texture transform to follow the
        // camera; Texture2D has no transform, so this is deferred to the Phase-4 billboard pass.
    }

    @Override // documentation inherited
    protected void loadTextures ()
    {
        super.loadTextures();

        if (_ticktexs == null) {
            // load up our various static textures
            _ticktexs = new Texture2D[5];
            for (int ii = 0; ii < _ticktexs.length; ii++) {
                _ticktexs[ii] = prepare("tick_counter_" + ii + ".png");
            }
            _movetex = prepare("move_order.png");
            _shoottex = prepare("shoot_order.png");
            _outtex = prepare("tick_outline.png");
            _routtex = prepare("tick_ready_outline.png");
        }
    }

    @Override // documentation inherited
    protected int numLayers ()
    {
        return 5;
    }

    @Override // documentation inherited
    protected int recolorLayers ()
    {
        return 4;
    }

    protected int _ticksToMove = -1;
    protected Influence _influence;
    protected UnitSprite.AdvanceOrder _pendo;

    protected static Texture2D[] _ticktexs;
    protected static Texture2D _outtex, _routtex, _movetex, _shoottex;

    protected static final float MOD_OFFSET = 3f * TILE_SIZE / 8f;
    protected static final Vector3f[] MOD_COORDS = {
        new Vector3f( MOD_OFFSET, -MOD_OFFSET, 0f),
        new Vector3f(-MOD_OFFSET, -MOD_OFFSET, 0f)
    };
}
