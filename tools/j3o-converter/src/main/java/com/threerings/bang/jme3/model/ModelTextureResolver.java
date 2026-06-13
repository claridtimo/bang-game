//
// $Id$

package com.threerings.bang.jme3.model;

import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.TextureKey;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.texture.Texture;

/**
 * The jME3 analogue of the fork's {@code ModelCache.ModelTextureProvider} — the runtime
 * indirection between a texture <em>name</em> stored in a model and the actual jME3
 * {@link Material}/{@link Texture} an instance renders with.
 *
 * <p>The fork resolves a stored name to a live texture state at instance time, applying
 * three runtime-only transforms (see {@code docs/jme3-model-loader.md} for the full design):
 * <ol>
 *   <li><b>Path mapping</b> — a name is relative to the model directory unless it starts with
 *       {@code /} (then rsrc-relative); {@code foo/../bar} segments are normalised. (Implemented.)</li>
 *   <li><b>Variant / random pick</b> — a multi-valued texture property means "choose one per
 *       instance" ({@code properties.random % textures.length}); a variant re-filters mesh
 *       properties by a {@code <variant>.} prefix. (Pick is implemented via {@link #variantIndex};
 *       variant re-filtering is fed by the loader from {@code bang.properties}.)</li>
 *   <li><b>Colorization</b> — nenya {@code Colorization[]} recolours a texture's pixels for team
 *       colours etc. (Designed; see {@link #colorize}. A {@code Colorization} hook is provided
 *       but the pixel recolour is left to the colorization port.)</li>
 * </ol>
 *
 * <p>It also carries the fork per-mesh render flags onto the placeholder material as far as the
 * stock {@code Lighting.j3md} allows (cull/blend/transparent bucket/alpha threshold/emissive).
 */
public class ModelTextureResolver
{
    public ModelTextureResolver (AssetManager assetManager)
    {
        this(assetManager, 0);
    }

    /**
     * @param variantIndex the per-instance selector used to pick among multi-valued texture
     * properties (mirrors {@code CloneCreator.random}); pass a stable value per instance.
     */
    public ModelTextureResolver (AssetManager assetManager, int variantIndex)
    {
        _assetManager = assetManager;
        _variantIndex = variantIndex;
    }

    /**
     * Applies a placeholder lit material to {@code geom}, resolving the default texture and
     * preserving the full texture list + render flags. Returns the resolved texture asset path
     * (or null if none/unresolved), for parity reporting.
     *
     * @param textures the resolved texture file name(s) from the mesh; >1 means "pick one".
     */
    public String applyMaterial (
        Geometry geom, String typePath, String textureKey, String[] textures,
        boolean solid, boolean additive, boolean transparent, float alphaThreshold,
        boolean emissive)
    {
        Material mat = new Material(_assetManager, "Common/MatDefs/Light/Lighting.j3md");
        String resolved = null;
        if (textures != null && textures.length > 0) {
            // multi-valued => per-instance pick; single-valued => the only entry
            String pick = textures[textures.length == 1 ? 0 : (_variantIndex % textures.length)];
            resolved = textureAssetPath(typePath, pick);
            try {
                TextureKey tkey = new TextureKey(resolved, false);
                Texture tex = _assetManager.loadTexture(tkey);
                tex.setWrap(Texture.WrapMode.Repeat);
                tex = colorize(tex); // colorization hook (no-op by default)
                mat.setTexture("DiffuseMap", tex);
            } catch (AssetNotFoundException e) {
                resolved = null; // leave untextured rather than failing the load
            }
            // preserve the full list and key so a re-resolve (variant/colorize) is possible
            geom.setUserData("bang.textureKey", textureKey);
            geom.setUserData("bang.textures", String.join(",", textures));
        }
        if (emissive) {
            mat.setColor("GlowColor", com.jme3.math.ColorRGBA.White);
        }
        if (!solid) {
            mat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        }
        if (additive) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.AlphaAdditive);
            geom.setQueueBucket(Bucket.Transparent);
        } else if (transparent) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", alphaThreshold);
            geom.setQueueBucket(Bucket.Transparent);
        }
        geom.setMaterial(mat);
        return resolved;
    }

    /**
     * Maps a stored texture name to an asset path relative to the rsrc root, mirroring
     * {@code ModelCache.ModelTextureProvider}: relative to the model dir unless {@code /}-prefixed.
     */
    public String textureAssetPath (String typePath, String name)
    {
        String path = name.startsWith("/") ? name.substring(1) : (typePath + "/" + name);
        String npath;
        while (!(npath = path.replaceFirst("[^/.]+/\\.\\./", "")).equals(path)) {
            path = npath;
        }
        return path;
    }

    /**
     * Colorization hook. The fork applies nenya {@code Colorization[]} recolourings here
     * (team colours, etc.) by remapping pixels in a paletted source. The jME3 design (see
     * {@code docs/jme3-model-loader.md}) recolours the {@link Texture}'s {@code Image} on a
     * cloned copy — or, preferably, supplies the colorization as a material parameter to a
     * recolouring MatDef. Default: identity (returns the texture unchanged).
     */
    protected Texture colorize (Texture tex)
    {
        return tex;
    }

    protected final AssetManager _assetManager;
    protected final int _variantIndex;
}
