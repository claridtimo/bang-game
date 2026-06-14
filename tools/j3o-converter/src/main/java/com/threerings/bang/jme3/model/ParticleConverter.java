//
// $Id$

package com.threerings.bang.jme3.model;

import java.util.ArrayList;
import java.util.List;

import com.jme.scene.state.RenderState;
import com.jme.scene.state.TextureState;
import com.jme.scene.state.AlphaState;
import com.jmex.effects.particles.ParticleGeometry;
import com.jmex.effects.particles.ParticleController;
import com.jmex.effects.particles.ParticleInfluence;
import com.jmex.effects.particles.SimpleParticleInfluenceFactory;

import com.jme3.asset.AssetManager;
import com.jme3.asset.TextureKey;
import com.jme3.effect.ParticleEmitter;
import com.jme3.effect.ParticleMesh.Type;
import com.jme3.effect.shapes.EmitterPointShape;
import com.jme3.effect.shapes.EmitterBoxShape;
import com.jme3.effect.shapes.EmitterSphereShape;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.texture.Texture;

/**
 * Build-time re-authoring bridge: converts a fork
 * {@link com.jmex.effects.particles.ParticleGeometry} graph (deserialized from a fork-format
 * {@code particles.jme} via the fork {@code BinaryImporter}) into a jMonkeyEngine&nbsp;3
 * {@link ParticleEmitter} tree, so the runtime {@code ParticleCache} can load a jME3-native
 * {@code effects/<key>/particles.j3o} through the stock {@code AssetManager}.
 *
 * <p>This is the particle analogue of {@link ModelConverter}: it lives on the fork-coupled
 * build-time classpath (it reads {@code com.jmex.effects.particles.*} / {@code com.jme.*}) and
 * writes pure jME3, so no fork dependency reaches the runtime.
 *
 * <h3>Parameter mapping (fork {@code ParticleGeometry} &rarr; jME3 {@code ParticleEmitter})</h3>
 * <p>The fork's per-instance deep-copy in the pre-cutover {@code ParticleCache} is the de-facto
 * spec of which params matter; this converter mirrors it:
 * <ul>
 *   <li>{@code numParticles} &rarr; {@code setNumParticles}; {@code releaseRate} (particles/sec,
 *       only honoured when the controller's {@code controlFlow} is set) &rarr;
 *       {@code setParticlesPerSec} (else a steady refill at count/mean-lifetime so a continuously
 *       cloned ambient effect keeps a full, looping population).</li>
 *   <li>{@code startSize}/{@code endSize} &rarr; {@code setStartSize}/{@code setEndSize}.</li>
 *   <li>{@code startColor}/{@code endColor} &rarr; {@code setStartColor}/{@code setEndColor}.</li>
 *   <li>{@code minimumLifeTime}/{@code maximumLifeTime} (fork ms) &rarr;
 *       {@code setLowLife}/{@code setHighLife} (jME3 seconds; divided by 1000).</li>
 *   <li>{@code emissionDirection} &times; {@code initialVelocity} &rarr;
 *       {@code getParticleInfluencer().setInitialVelocity(...)}; the fork emits in a cone up to
 *       {@code maximumAngle} about that direction &rarr; {@code setVelocityVariation(...)}
 *       (1.0 at a full hemisphere / {@code PI/2} max angle).</li>
 *   <li>{@code particleSpinSpeed} &rarr; {@code setRotateSpeed} + {@code setRandomAngle(true)};
 *       {@code velocityAligned} &rarr; {@code setFacingVelocity(true)}.</li>
 *   <li>emit type (point/line/rectangle/ring) &rarr; {@code setShape(...)}: ET_POINT &rarr;
 *       {@link EmitterPointShape}; ET_RECTANGLE &rarr; {@link EmitterBoxShape}; ET_RING &rarr;
 *       {@link EmitterSphereShape} (closest stock shape); ET_LINE &rarr; a thin
 *       {@link EmitterBoxShape}.</li>
 *   <li>{@link SimpleParticleInfluenceFactory.BasicGravity} &rarr; {@code setGravity(...)};
 *       {@link SimpleParticleInfluenceFactory.BasicWind} folded into the gravity vector
 *       (jME3 has no separate wind force); {@code BasicDrag}/{@code SwarmInfluence}/
 *       {@code WanderInfluence} have <b>no jME3 equivalent</b> and are recorded as dropped.</li>
 *   <li>texture (the fork {@code TextureState}'s first texture's image location) &rarr; a
 *       {@code Common/MatDefs/Misc/Particle.j3md} material with that texture, {@code ImagesX=1}/
 *       {@code ImagesY=1} (the fork particle system carries no sprite sub-image grid); blend mode
 *       from the {@code AlphaState} (additive {@code DB_ONE} &rarr; {@link BlendMode#Additive},
 *       else {@link BlendMode#Alpha}).</li>
 *   <li>geometry type: fork {@code ParticleMesh} (quads, {@code PT_QUAD}) &rarr;
 *       {@link Type#Triangle} (jME3's quad-per-particle mesh); {@code PT_POINT}/{@code PT_LINE}
 *       degrade to triangle particles (jME3 stock has no point/line emitter) and are recorded.</li>
 * </ul>
 */
public class ParticleConverter
{
    /** What was and was not converted for a single particles.jme. */
    public static class Result
    {
        /** The converted jME3 root (a Node of one or more ParticleEmitters). */
        public Spatial root;
        /** Number of ParticleEmitters produced. */
        public int emitters;
        /** Fork influence/feature class names with no jME3 equivalent (recorded, not converted). */
        public final List<String> droppedInfluences = new ArrayList<>();
        /** Notes about lossy/degraded mappings (point/line particles, ring->sphere, etc.). */
        public final List<String> notes = new ArrayList<>();
        /** True if every emitter found a texture. */
        public boolean texturesResolved = true;
    }

    public ParticleConverter (AssetManager assetManager, String typePath)
    {
        _assets = assetManager;
        _typePath = typePath;
    }

    /**
     * Converts a fork particle spatial (a {@link ParticleGeometry} or a {@code com.jme.scene.Node}
     * of them) to a jME3 {@link Spatial}.
     */
    public Result convert (com.jme.scene.Spatial forkRoot)
    {
        Result result = new Result();
        result.root = convertSpatial(forkRoot, result);
        return result;
    }

    protected Spatial convertSpatial (com.jme.scene.Spatial spatial, Result result)
    {
        if (spatial instanceof ParticleGeometry geom) {
            ParticleEmitter emitter = convertEmitter(geom, result);
            copyTransform(spatial, emitter);
            result.emitters++;
            return emitter;
        } else if (spatial instanceof com.jme.scene.Node fnode) {
            Node node = new Node(name(spatial, "effect"));
            for (int ii = 0, nn = fnode.getQuantity(); ii < nn; ii++) {
                Spatial child = convertSpatial(fnode.getChild(ii), result);
                if (child != null) {
                    node.attachChild(child);
                }
            }
            copyTransform(spatial, node);
            return node;
        }
        result.notes.add("skipped non-particle spatial " + spatial.getClass().getName());
        return null;
    }

    protected ParticleEmitter convertEmitter (ParticleGeometry geom, Result result)
    {
        int count = Math.max(1, geom.getNumParticles());

        // fork PT_QUAD -> jME3 Triangle (quad-per-particle) mesh; point/line degrade to triangle.
        int ptype = geom.getParticleType();
        if (ptype == ParticleGeometry.PT_POINT) {
            result.notes.add(name(geom, "?") + ": PT_POINT degraded to triangle particles");
        } else if (ptype == ParticleGeometry.PT_LINE) {
            result.notes.add(name(geom, "?") + ": PT_LINE degraded to triangle particles");
        }

        ParticleEmitter emitter =
            new ParticleEmitter(name(geom, "emitter"), Type.Triangle, count);

        // ---- appearance ----
        emitter.setStartSize(geom.getStartSize());
        emitter.setEndSize(geom.getEndSize());
        emitter.setStartColor(color(geom.getStartColor()));
        emitter.setEndColor(color(geom.getEndColor()));

        // ---- lifetime (fork ms -> jME3 seconds) ----
        float low = Math.max(MIN_LIFE_SEC, geom.getMinimumLifeTime() / 1000f);
        float high = Math.max(low, geom.getMaximumLifeTime() / 1000f);
        emitter.setLowLife(low);
        emitter.setHighLife(high);

        // ---- count / flow ----
        emitter.setNumParticles(count);
        ParticleController ctrl = geom.getParticleController();
        boolean controlFlow = ctrl != null && ctrl.isControlFlow();
        if (controlFlow && geom.getReleaseRate() > 0) {
            emitter.setParticlesPerSec(geom.getReleaseRate());
        } else {
            // no controlled flow: emit the whole pool steadily over the mean lifetime so a
            // continuously-cloned ambient effect keeps a full, looping population (a true one-shot
            // burst would empty after one mean lifetime; ParticleCache clones a fresh instance per
            // spawn, so a steady refill matches the fork's wrap/cycle behaviour best).
            float mean = (low + high) * 0.5f;
            emitter.setParticlesPerSec(mean > 0 ? count / mean : count);
        }

        // ---- emission cone (direction * velocity, spread from min/max angle) ----
        com.jme.math.Vector3f dir = geom.getEmissionDirection();
        float vel = geom.getInitialVelocity();
        Vector3f initial = new Vector3f(dir.x, dir.y, dir.z);
        if (initial.lengthSquared() < 1e-8f) {
            initial.set(0, 1, 0);
        }
        initial.normalizeLocal().multLocal(vel);
        emitter.getParticleInfluencer().setInitialVelocity(initial);
        // map the fork emission cone [minAngle, maxAngle] (radians off the direction) to jME3's
        // single 0..1 velocity variation: full hemisphere (maxAngle >= PI/2) -> 1.0.
        float maxAngle = geom.getMaximumAngle();
        float variation = clamp01(maxAngle / com.jme.math.FastMath.HALF_PI);
        emitter.getParticleInfluencer().setVelocityVariation(variation);

        // ---- spin / alignment ----
        float spin = geom.getParticleSpinSpeed();
        if (spin != 0f) {
            emitter.setRotateSpeed(Math.abs(spin));
            emitter.setRandomAngle(true);
        }
        if (geom.isVelocityAligned()) {
            emitter.setFacingVelocity(true);
        }

        // ---- emitter shape ----
        emitter.setShape(shapeFor(geom, result));

        // ---- influences (gravity / wind) ----
        applyInfluences(geom, emitter, result);

        // ---- material (texture + blend) ----
        applyMaterial(geom, emitter, result);

        // The fork's transformParticles==false emits particles into world space (they don't follow
        // the emitter once spawned); transformParticles==true keeps them in emitter-local space.
        // jME3's setInWorldSpace(true) is the world-space (non-following) behaviour.
        emitter.setInWorldSpace(!geom.isTransformParticles());

        return emitter;
    }

    protected com.jme3.effect.shapes.EmitterShape shapeFor (ParticleGeometry geom, Result result)
    {
        switch (geom.getEmitType()) {
        case ParticleGeometry.ET_RECTANGLE: {
            com.jme.math.Rectangle r = geom.getRectangle();
            if (r != null) {
                com.jme.math.Vector3f a = r.getA(), b = r.getB(), c = r.getC();
                // Rectangle is defined by corner A and edge vectors to B and C; build a box that
                // spans those corners (zero-thickness on the unused axis).
                Vector3f min = new Vector3f(
                    Math.min(a.x, Math.min(b.x, c.x)),
                    Math.min(a.y, Math.min(b.y, c.y)),
                    Math.min(a.z, Math.min(b.z, c.z)));
                Vector3f max = new Vector3f(
                    Math.max(a.x, Math.max(b.x, c.x)),
                    Math.max(a.y, Math.max(b.y, c.y)),
                    Math.max(a.z, Math.max(b.z, c.z)));
                return new EmitterBoxShape(min, max);
            }
            break;
        }
        case ParticleGeometry.ET_RING: {
            com.jme.math.Ring ring = geom.getRing();
            if (ring != null) {
                result.notes.add(name(geom, "?") + ": ET_RING approximated by sphere shape");
                com.jme.math.Vector3f c = ring.getCenter();
                return new EmitterSphereShape(new Vector3f(c.x, c.y, c.z),
                    Math.max(0.01f, ring.getOuterRadius()));
            }
            break;
        }
        case ParticleGeometry.ET_LINE: {
            com.jme.math.Line line = geom.getLine();
            if (line != null) {
                // a thin box spanning the line's origin..(origin+direction)
                com.jme.math.Vector3f o = line.getOrigin(), d = line.getDirection();
                Vector3f min = new Vector3f(
                    Math.min(o.x, o.x + d.x), Math.min(o.y, o.y + d.y), Math.min(o.z, o.z + d.z));
                Vector3f max = new Vector3f(
                    Math.max(o.x, o.x + d.x), Math.max(o.y, o.y + d.y), Math.max(o.z, o.z + d.z));
                result.notes.add(name(geom, "?") + ": ET_LINE approximated by thin box shape");
                return new EmitterBoxShape(min, max);
            }
            break;
        }
        case ParticleGeometry.ET_POINT:
        default:
            break;
        }
        com.jme.math.Vector3f off = geom.getOriginOffset();
        // originOffset (and the rectangle/ring/line above) can be null on a fork emitter; fall back
        // to the origin rather than NPEing and failing the whole corpus bake.
        return (off == null) ? new EmitterPointShape(new Vector3f(0, 0, 0))
                             : new EmitterPointShape(new Vector3f(off.x, off.y, off.z));
    }

    protected void applyInfluences (ParticleGeometry geom, ParticleEmitter emitter, Result result)
    {
        List<ParticleInfluence> infs = geom.getInfluences();
        if (infs == null) {
            return;
        }
        Vector3f gravity = new Vector3f(0, 0, 0);
        boolean haveGravity = false;
        for (ParticleInfluence inf : infs) {
            if (inf instanceof SimpleParticleInfluenceFactory.BasicGravity g) {
                com.jme.math.Vector3f f = g.getGravityForce();
                // jME3 gravity is an acceleration vector SUBTRACTED from velocity each step; the
                // fork gravity force is ADDED. Negate to match (a fork (0,-9.8,0) downward force ->
                // jME3 gravity (0,9.8,0), which jME3 subtracts -> downward).
                gravity.addLocal(-f.x, -f.y, -f.z);
                haveGravity = true;
            } else if (inf instanceof SimpleParticleInfluenceFactory.BasicWind w) {
                // jME3 has no wind force; fold the wind into the gravity vector as a constant
                // lateral acceleration (best-effort, same sign convention as gravity above).
                com.jme.math.Vector3f wd = w.getWindDirection();
                float s = w.getStrength();
                gravity.addLocal(-wd.x * s, -wd.y * s, -wd.z * s);
                haveGravity = true;
                result.notes.add(name(geom, "?") + ": BasicWind folded into gravity (no jME3 wind)");
            } else {
                result.droppedInfluences.add(inf.getClass().getName());
            }
        }
        if (haveGravity) {
            emitter.setGravity(gravity);
        } else {
            // the fork applies no gravity unless a BasicGravity influence is present; jME3's default
            // gravity is (0,0.1,0), so zero it to match a no-gravity fork effect.
            emitter.setGravity(0, 0, 0);
        }
    }

    protected void applyMaterial (ParticleGeometry geom, ParticleEmitter emitter, Result result)
    {
        Material mat = new Material(_assets, "Common/MatDefs/Misc/Particle.j3md");

        // texture: the fork TextureState's first texture's image location (relative to the effect
        // dir; the fork set a TextureKey location override to that dir, so it's a bare filename).
        String texPath = textureLocation(geom);
        if (texPath != null) {
            String assetPath = resolveTexturePath(texPath);
            try {
                Texture tex = _assets.loadTexture(new TextureKey(assetPath, false));
                mat.setTexture("Texture", tex);
            } catch (Exception e) {
                result.texturesResolved = false;
                result.notes.add(name(geom, "?") + ": texture not found: " + assetPath);
            }
        } else {
            result.texturesResolved = false;
            result.notes.add(name(geom, "?") + ": no TextureState/texture on emitter");
        }

        // blend mode from the fork AlphaState (additive dst==ONE -> Additive, else Alpha).
        BlendMode blend = BlendMode.Alpha;
        AlphaState astate = (AlphaState)geom.getRenderState(RenderState.RS_ALPHA);
        if (astate != null && astate.isBlendEnabled() &&
                astate.getDstFunction() == AlphaState.DB_ONE) {
            blend = BlendMode.Additive;
        }
        mat.getAdditionalRenderState().setBlendMode(blend);

        // single texture, no sprite sub-image animation in the fork particle system.
        emitter.setImagesX(1);
        emitter.setImagesY(1);
        emitter.setMaterial(mat);
    }

    protected String textureLocation (ParticleGeometry geom)
    {
        TextureState ts = (TextureState)geom.getRenderState(RenderState.RS_TEXTURE);
        if (ts == null) {
            return null;
        }
        com.jme.image.Texture tex = ts.getTexture();
        if (tex == null) {
            return null;
        }
        String loc = tex.getImageLocation();
        if ((loc == null || loc.isEmpty()) && tex.getTextureKey() != null &&
                tex.getTextureKey().getLocation() != null) {
            loc = tex.getTextureKey().getLocation().toString();
        }
        return (loc == null || loc.isEmpty()) ? null : loc;
    }

    /**
     * Turns a fork texture image location into an AssetManager-relative path. The fork stored
     * texture references relative to the effect dir, and the build-time {@code TextureKey} location
     * override (mirroring the fork {@code ParticleCache}) resolves them to an absolute
     * {@code file:.../rsrc/<asset path>} URL — and crucially the effects routinely reference
     * <em>shared</em> textures in OTHER effect dirs (e.g. {@code boom_town/barrel_explosion} reuses
     * {@code frontier_town/explosion/explosion.png}). So the correct asset path is everything after
     * the {@code rsrc/} root in the resolved location, not the bare filename under the effect dir.
     *
     * <p>Falls back to {@code effects/<key>/<filename>} for a bare relative location with no
     * {@code rsrc/} marker.
     */
    protected String resolveTexturePath (String loc)
    {
        String s = loc.replace('\\', '/');
        int marker = s.indexOf("/rsrc/");
        if (marker >= 0) {
            return s.substring(marker + "/rsrc/".length());
        }
        if (s.startsWith("rsrc/")) {
            return s.substring("rsrc/".length());
        }
        // bare relative filename: resolve under the effect dir
        int slash = s.lastIndexOf('/');
        String file = slash >= 0 ? s.substring(slash + 1) : s;
        return "effects/" + _typePath + "/" + file;
    }

    protected static void copyTransform (com.jme.scene.Spatial from, Spatial to)
    {
        com.jme.math.Vector3f t = from.getLocalTranslation();
        com.jme.math.Quaternion r = from.getLocalRotation();
        com.jme.math.Vector3f s = from.getLocalScale();
        to.setLocalTranslation(t.x, t.y, t.z);
        to.setLocalRotation(new Quaternion(r.x, r.y, r.z, r.w));
        to.setLocalScale(s.x, s.y, s.z);
    }

    protected static ColorRGBA color (com.jme.renderer.ColorRGBA c)
    {
        return c == null ? new ColorRGBA(1, 1, 1, 1) : new ColorRGBA(c.r, c.g, c.b, c.a);
    }

    protected static String name (com.jme.scene.Spatial s, String dflt)
    {
        String n = s.getName();
        return (n == null || n.isEmpty()) ? dflt : n;
    }

    protected static float clamp01 (float v)
    {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    protected final AssetManager _assets;
    protected final String _typePath;

    /** jME3 rejects a zero/near-zero low life. */
    protected static final float MIN_LIFE_SEC = 0.01f;
}
