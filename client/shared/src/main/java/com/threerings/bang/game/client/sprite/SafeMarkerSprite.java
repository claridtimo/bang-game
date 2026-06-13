//
// $Id$

package com.threerings.bang.game.client.sprite;

import com.jme3.material.Material;
import com.jme3.texture.Texture2D;

import com.samskivert.util.HashIntMap;

import com.threerings.bang.util.BasicContext;
import com.threerings.bang.util.RenderUtil;

import com.threerings.bang.game.client.BoardView;
import com.threerings.bang.game.data.BangBoard;
import com.threerings.bang.game.data.piece.Marker;
import com.threerings.bang.game.data.piece.Piece;
import com.threerings.bang.game.data.piece.SafeMarker;

import com.threerings.openal.SoundGroup;

/**
 * Sprite for the safe markers.
 */
public class SafeMarkerSprite extends MarkerSprite
{
    public SafeMarkerSprite (int type)
    {
        super(type);
    }

    @Override // documentation inherited
    public void init (BasicContext ctx, BoardView view, BangBoard board,
            SoundGroup sounds, Piece piece, short tick)
    {
        super.init(ctx, view, board, sounds, piece, tick);
        int type = ((Marker)_piece).getType();
        // jME3: the fork built 8 multi-unit TextureStates (on/off x 4 texture rotations) and an
        // emissive overlay unit. Texture2D carries no rotation transform and the emissive overlay
        // is a custom-MatDef concern, so for the Phase-2 port we build one textured Material per
        // on/off state (the directional rotation + emissive overlay are deferred to the Phase-4
        // material pass; the safe marker still shows its on/off state, just not rotated per facing).
        Material[] mats = _highlightMaterials.get(type);
        if (mats == null) {
            mats = new Material[HIGHLIGHT_TEXS.length];
            String root = (String)SPRITES[type*2+1];
            for (int ii = 0; ii < HIGHLIGHT_TEXS.length; ii++) {
                Texture2D btex = ctx.getTextureCache().getTexture(
                    root + HIGHLIGHT_TEXS[ii] + ".png");
                mats[ii] = RenderUtil.createTextureMaterial(ctx, btex);
                RenderUtil.applyBlendAlpha(mats[ii]);
                RenderUtil.applyOverlayZBuf(mats[ii]);
            }
            _highlightMaterials.put(type, mats);
        }
        _tlight = view.getTerrainNode().createHighlight(
                piece.x, piece.y, false, (byte)1);
        setOrientation(piece.orientation);
        attachHighlight(_tlight);
    }

    @Override // documenatation inherited
    public void setOrientation (int orientation)
    {
        setOnOff(orientation, ((SafeMarker)_piece).isOn());
    }

    @Override // documentation inherited
    public void updated (Piece piece, short tick)
    {
        super.updated(piece, tick);
        setOnOff(_piece.orientation, ((SafeMarker)_piece).isOn());
    }

    /**
     * Set the on/off texture for a highlight marker.
     */
    public void setOnOff (int orientation, boolean on)
    {
        if (_tlight != null) {
            // index 0 = on, 1 = off (the per-direction rotation variants are deferred to Phase 4).
            Material mat = _highlightMaterials.get(
                ((Marker)_piece).getType())[on ? 0 : 1];
            _tlight.setTextures(mat, mat);
        }
    }

    protected static final String[] HIGHLIGHT_TEXS = {
        "_on", "_off"
    };

    protected static final String[] EMISSIVE_TEXS = {
        "_on_emis", "_off_emis"
    };

    protected static HashIntMap<Material[]> _highlightMaterials =
        new HashIntMap<Material[]>();
}
