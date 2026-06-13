//
// $Id$

package com.threerings.bang.client.util;

import java.lang.ref.WeakReference;

import java.awt.image.BufferedImage;

import java.util.Arrays;
import java.util.HashMap;

import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;

import com.jmex.bui.util.Rectangle;

import com.threerings.jme.util.ImageCache;
import com.threerings.media.image.Colorization;

import com.threerings.bang.util.BasicContext;
import com.threerings.bang.util.RenderUtil;

import static com.threerings.bang.Log.log;

/**
 * Implements a simple weak reference based texture cache.
 *
 * <p>jME3 cutover (Phase 2, cluster 6): the fork cached {@code com.jme.image.Texture} state objects
 * and hand-managed their GL texture ids (the {@code CachedTexture}/{@code TextureReference}
 * id-sharing machinery, plus a dummy {@code TextureState} to delete ids). jME3 owns texture upload
 * and GL-name lifecycle, so all of that is gone: the cache now holds weak references to
 * {@link Texture2D} objects built from the jME3 {@link Image}s the {@link ImageCache} produces, and
 * the {@link Texture2D} is reclaimed by the GC / jME3 when no longer referenced. The public
 * {@code getTexture(...)}/{@code createTexture()} surface is preserved (return type fork
 * {@code Texture} → {@link Texture2D}).
 */
public class TextureCache
{
    public TextureCache (BasicContext ctx)
    {
        _ctx = ctx;
    }

    /**
     * Creates a texture from the image with the specified path.
     */
    public Texture2D getTexture (String path)
    {
        return getTexture(path, (Colorization[])null, 1f);
    }

    /**
     * Creates a texture from the image with the specified path and scale.
     */
    public Texture2D getTexture (String path, float scale)
    {
        return getTexture(path, (Colorization[])null, scale);
    }

    /**
     * Creates a texture from the image with the specified path and colorizations.
     */
    public Texture2D getTexture (String path, Colorization[] zations)
    {
        return getTexture(path, zations, 1f);
    }

    /**
     * Creates a texture from the image with the specified path, colorizations, and scale.
     */
    public Texture2D getTexture (String path, Colorization[] zations, float scale)
    {
        TextureKey tkey = new TextureKey(path, zations, null);
        WeakReference<Texture2D> ref = _textures.get(tkey);
        Texture2D texture = (ref == null) ? null : ref.get();
        if (texture != null) {
            return texture;
        }

        // if the image is not recolorable, try again without the colorizations
        Image img;
        if (zations != null) {
            BufferedImage bimg = _ctx.getImageCache().getBufferedImage(path);
            if (bimg.getType() != BufferedImage.TYPE_BYTE_INDEXED) {
                return getTexture(path, scale);
            }
            img = ImageCache.createImage(bimg, zations, scale, true);
        } else {
            img = _ctx.getImageCache().getImage(path, scale);
        }
        texture = new Texture2D();
        RenderUtil.configureTexture(texture, img);
        texture.setName(path);
        _textures.put(tkey, new WeakReference<Texture2D>(texture));
        return texture;
    }

    /**
     * Creates a texture using the specified region from the image with the specified path.
     */
    public Texture2D getTexture (String path, Rectangle region)
    {
        return getTexture(path, null, region);
    }

    /**
     * Creates a texture using the specified region from the image with the specified path.
     */
    public Texture2D getTexture (
        String path, Colorization[] zations, Rectangle region)
    {
        TextureKey tkey = new TextureKey(path, zations, region);
        WeakReference<Texture2D> ref = _textures.get(tkey);
        Texture2D texture = (ref == null) ? null : ref.get();
        if (texture != null) {
            return texture;
        }

        BufferedImage image = _ctx.getImageCache().getBufferedImage(path);
        if (region.width != region.height) {
            log.warning("Requested to create sub-image texture of non-equal width and height",
                        "path", path, "region", region);
        }

        BufferedImage subimg = image.getSubimage(
            region.x, region.y, region.width, region.height);
        Image img = (zations == null) ?
            ImageCache.createImage(subimg, true) :
            ImageCache.createImage(subimg, zations, true);
        texture = new Texture2D();
        RenderUtil.configureTexture(texture, img);
        texture.setName(path);
        _textures.put(tkey, new WeakReference<Texture2D>(texture));
        return texture;
    }

    /**
     * Creates a texture using the specified "tile" from the image with the specified path.
     */
    public Texture2D getTexture (String path, int tileWidth, int tileHeight,
                               int tilesPerRow, int tileIndex)
    {
        int tx = tileIndex % tilesPerRow, ty = tileIndex / tilesPerRow;
        Rectangle region = new Rectangle(
            tx * tileWidth, ty * tileHeight, tileWidth, tileHeight);
        return getTexture(path, region);
    }

    /**
     * Creates a new, empty texture. The caller configures it (typically via
     * {@link RenderUtil#configureTexture}). jME3 reclaims the GL texture when this object is no
     * longer referenced.
     */
    public Texture2D createTexture ()
    {
        return new Texture2D();
    }

    /**
     * Computes the count of resident and non-resident cached textures (debug aid).
     */
    public void dumpResidence ()
    {
        int live = 0, flushed = 0;
        for (WeakReference<Texture2D> ref : _textures.values()) {
            if (ref.get() == null) {
                flushed++;
            } else {
                live++;
            }
        }
        log.info("TextureCache live:" + live + " flushed:" + flushed);
    }

    protected static class TextureKey
    {
        public String path;
        public Colorization[] zations;
        public Rectangle region;

        public TextureKey (
            String path, Colorization[] zations, Rectangle region) {
            this.path = path;
            this.zations = zations;
            this.region = region;
        }

        public boolean equals (Object other) {
            TextureKey otk = (TextureKey)other;
            return otk.path.equals(path) &&
                Arrays.equals(otk.zations, zations) &&
                ((region == null && otk.region == null) ||
                 (region != null && otk.region != null &&
                  otk.region.equals(region)));
        }

        public int hashCode () {
            return path.hashCode() ^ Arrays.hashCode(zations) ^
                (region == null ? 0 : region.hashCode());
        }

        public String toString () {
            return path + ":" + zations + ":" + region;
        }
    }

    protected BasicContext _ctx;

    /** The cached textures. */
    protected HashMap<TextureKey, WeakReference<Texture2D>> _textures =
        new HashMap<TextureKey, WeakReference<Texture2D>>();
}
