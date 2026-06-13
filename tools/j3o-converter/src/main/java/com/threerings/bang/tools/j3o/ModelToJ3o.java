//
// $Id$

package com.threerings.bang.tools.j3o;

import java.io.File;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;

import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.asset.TextureKey;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryLoader;
import com.jme3.material.Material;
import com.jme3.material.RenderState.BlendMode;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Texture;
import com.jme3.texture.plugins.AWTLoader;

import com.threerings.jme.model.Model;
import com.threerings.jme.model.ModelMesh;
import com.threerings.jme.model.ModelMeshAccess;
import com.threerings.jme.model.ModelNode;
import com.threerings.jme.model.SkinMesh;

/**
 * Phase 5 prototype: converts a compiled Bang model ({@code model.dat}, the vendored jME
 * fork's binary format) into a jMonkeyEngine 3 {@code .j3o}, then re-imports the result
 * headlessly and verifies mesh-stat parity (vertex/triangle counts, texture references).
 *
 * <p>Scope: geometry, node hierarchy, transforms, UVs, and texture path mapping, with a
 * placeholder {@code Lighting.j3md} material. Animations, skinning weights, and
 * procedural/emission controllers are detected and reported but not converted (see
 * {@code docs/jme3-model-pipeline.md} §6 for the conversion plan).
 *
 * <p>Usage: {@code ModelToJ3o <model.dat> <output dir> <rsrc root>} where {@code <rsrc
 * root>} is the resource tree holding the model's textures (normally {@code assets/rsrc};
 * the {@code model.dat} itself may come from the staging copy). Runs fully headless.
 */
public class ModelToJ3o
{
    /** Per-geometry statistics used for parity verification. */
    public record GeoStats (String path, int vertices, int triangles, String texture) { }

    public static void main (String[] args)
        throws Exception
    {
        if (args.length != 3) {
            System.err.println("Usage: ModelToJ3o <model.dat> <output dir> <rsrc root>");
            System.exit(1);
        }
        File datFile = new File(args[0]).getAbsoluteFile();
        File outDir = new File(args[1]).getAbsoluteFile();
        File rsrcRoot = new File(args[2]).getAbsoluteFile();
        if (!datFile.isFile() || !rsrcRoot.isDirectory()) {
            System.err.println("model.dat and rsrc root must exist.");
            System.exit(1);
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) {
            System.err.println("Unable to create output directory " + outDir + ".");
            System.exit(1);
        }

        // the model's directory relative to the resource root, used both to name the
        // output and to resolve texture references the way ModelCache does at runtime
        String typePath = modelTypePath(datFile);

        // load the fork model exactly the way the model compiler does: headless against
        // a DummyDisplaySystem (render state setup is skipped when no real display)
        new com.jme.util.DummyDisplaySystem();
        com.jme.util.LoggingSystem.getLogger().setLevel(Level.WARNING);
        Model model = Model.readFromFile(datFile);

        DesktopAssetManager assetManager = createAssetManager(rsrcRoot, outDir);

        // convert and report what the prototype does not cover
        List<GeoStats> expected = new ArrayList<>();
        Node converted = new ModelToJ3o(assetManager, typePath).convert(model, expected);
        String[] anims = model.getAnimationNames();
        int controllers = model.getControllers().size();
        if (anims.length > 0) {
            System.out.println("NOTE: dropping " + anims.length + " animation(s) " +
                "(not converted by this prototype).");
        }
        if (controllers > 0) {
            System.out.println("NOTE: dropping " + controllers + " controller(s) " +
                "(not converted by this prototype).");
        }

        // export to .j3o
        String j3oName = model.getName() + ".j3o";
        File j3oFile = new File(outDir, j3oName);
        BinaryExporter.getInstance().save(converted, j3oFile);
        System.out.println("Wrote " + j3oFile + " (" + j3oFile.length() + " bytes).");

        // re-import headlessly through the stock jME3 asset pipeline and verify parity
        Spatial reloaded = assetManager.loadModel(new ModelKey(j3oName));
        List<GeoStats> actual = new ArrayList<>();
        collectStats(reloaded, "", actual);

        boolean ok = verify(expected, actual);
        System.exit(ok ? 0 : 2);
    }

    protected ModelToJ3o (DesktopAssetManager assetManager, String typePath)
    {
        _assetManager = assetManager;
        _typePath = typePath;
    }

    /**
     * Converts the fork model into a jME3 scenegraph, recording the per-geometry stats
     * that the re-imported {@code .j3o} is expected to reproduce.
     */
    protected Node convert (Model model, List<GeoStats> stats)
    {
        Node root = (Node)convertSpatial(model, "", stats);

        // preserve fork-only metadata as user data so nothing is lost in the round trip
        Properties props = model.getProperties();
        StringBuilder buf = new StringBuilder();
        for (String key : props.stringPropertyNames().stream().sorted().toList()) {
            buf.append(key).append(" = ").append(props.getProperty(key)).append('\n');
        }
        root.setUserData("bang.properties", buf.toString());
        root.setUserData("bang.animations", String.join(",", model.getAnimationNames()));
        root.setUserData("bang.type", _typePath);
        return root;
    }

    /**
     * Recursively converts a fork spatial (ModelNode/ModelMesh tree) to jME3. Returns
     * {@code null} for spatials this prototype does not convert (e.g. procedural
     * {@code ParticleMesh} effect geometry, present in 8 of the 310 models), which the
     * caller skips rather than crashing on.
     */
    protected Spatial convertSpatial (
        com.jme.scene.Spatial spatial, String path, List<GeoStats> stats)
    {
        String name = spatial.getName();
        String childPath = path.isEmpty() ? name : (path + "/" + name);
        Spatial converted;
        if (spatial instanceof ModelMesh mesh) {
            converted = convertMesh(mesh, childPath, stats);
        } else if (spatial instanceof com.jme.scene.Node fnode) {
            Node node = new Node(name);
            for (int ii = 0, nn = fnode.getQuantity(); ii < nn; ii++) {
                Spatial child = convertSpatial(fnode.getChild(ii), childPath, stats);
                if (child != null) {
                    node.attachChild(child);
                }
            }
            converted = node;
        } else {
            // procedural effect geometry (ParticleMesh, etc.); reproduced at runtime
            // from the model's controllers, not stored as convertible mesh data
            System.out.println("NOTE: skipping " + childPath + " (" +
                spatial.getClass().getName() + "; not convertible geometry).");
            return null;
        }
        copyTransform(spatial, converted);
        return converted;
    }

    /** Converts one fork mesh to a jME3 Geometry with a placeholder lit material. */
    protected Geometry convertMesh (ModelMesh fmesh, String path, List<GeoStats> stats)
    {
        if (fmesh instanceof SkinMesh) {
            System.out.println("NOTE: " + path + " is a SkinMesh; converting base pose " +
                "only (skinning weights not converted by this prototype).");
        }

        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, prepare(fmesh.getVertexBuffer(0)));
        FloatBuffer nbuf = fmesh.getNormalBuffer(0);
        if (nbuf != null) {
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, prepare(nbuf));
        }
        FloatBuffer tbuf = fmesh.getTextureBuffer(0, 0);
        if (tbuf != null) {
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, prepare(tbuf));
        }
        IntBuffer ibuf = fmesh.getIndexBuffer(0);
        ibuf.clear();
        mesh.setBuffer(VertexBuffer.Type.Index, 3, ibuf);
        mesh.updateBound();

        Geometry geom = new Geometry(fmesh.getName(), mesh);

        // placeholder material: Lighting.j3md with the model's diffuse texture
        Material mat = new Material(_assetManager, "Common/MatDefs/Light/Lighting.j3md");
        String texture = null;
        String[] textures = ModelMeshAccess.textures(fmesh);
        if (textures != null && textures.length > 0) {
            // multiple entries mean "pick one at random per instance"; bake the first
            // as the default and keep the full list for the runtime model cache
            texture = textureAssetPath(textures[0]);
            try {
                TextureKey tkey = new TextureKey(texture, false);
                Texture tex = _assetManager.loadTexture(tkey);
                tex.setWrap(Texture.WrapMode.Repeat);
                mat.setTexture("DiffuseMap", tex);
            } catch (AssetNotFoundException e) {
                System.out.println("WARNING: texture not found, leaving material " +
                    "untextured [path=" + path + ", texture=" + texture + "].");
                texture = null;
            }
            geom.setUserData("bang.textureKey", ModelMeshAccess.textureKey(fmesh));
            geom.setUserData("bang.textures", String.join(",", textures));
        }

        // carry the fork render flags as far as the placeholder material allows
        if (!ModelMeshAccess.solid(fmesh)) {
            mat.getAdditionalRenderState().setFaceCullMode(FaceCullMode.Off);
        }
        if (ModelMeshAccess.additive(fmesh)) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.AlphaAdditive);
            geom.setQueueBucket(Bucket.Transparent);
        } else if (ModelMeshAccess.transparent(fmesh)) {
            mat.getAdditionalRenderState().setBlendMode(BlendMode.Alpha);
            mat.setFloat("AlphaDiscardThreshold", ModelMeshAccess.alphaThreshold(fmesh));
            geom.setQueueBucket(Bucket.Transparent);
        }
        geom.setMaterial(mat);

        stats.add(new GeoStats(
            path, mesh.getVertexCount(), mesh.getTriangleCount(), texture));
        return geom;
    }

    /**
     * Maps a texture name stored in a mesh to an asset path relative to the rsrc root,
     * mirroring {@code ModelCache.ModelTextureProvider}: names are relative to the model
     * directory unless prefixed with {@code /}, which makes them rsrc-relative.
     */
    protected String textureAssetPath (String name)
    {
        String path = name.startsWith("/") ? name.substring(1) : (_typePath + "/" + name);
        // normalize any foo/../bar segments the way ModelCache.cleanPath does
        String npath;
        while (!(npath = path.replaceFirst("[^/.]+/\\.\\./", "")).equals(path)) {
            path = npath;
        }
        return path;
    }

    /** Copies the local transform from a fork spatial to a jME3 spatial. */
    protected static void copyTransform (com.jme.scene.Spatial from, Spatial to)
    {
        com.jme.math.Vector3f t = from.getLocalTranslation();
        com.jme.math.Quaternion r = from.getLocalRotation();
        com.jme.math.Vector3f s = from.getLocalScale();
        to.setLocalTranslation(t.x, t.y, t.z);
        to.setLocalRotation(new com.jme3.math.Quaternion(r.x, r.y, r.z, r.w));
        to.setLocalScale(s.x, s.y, s.z);
    }

    /** Resets a buffer to cover its full capacity (jME3 sizes buffers by limit). */
    protected static FloatBuffer prepare (FloatBuffer buf)
    {
        buf.clear();
        return buf;
    }

    /** A minimal headless asset manager: classpath (material defs), rsrc (textures),
     * and the output directory (.j3o re-import). */
    protected static DesktopAssetManager createAssetManager (File rsrcRoot, File outDir)
    {
        DesktopAssetManager am = new DesktopAssetManager();
        am.registerLocator("/", ClasspathLocator.class);
        am.registerLocator(rsrcRoot.getPath(), FileLocator.class);
        am.registerLocator(outDir.getPath(), FileLocator.class);
        am.registerLoader(J3MLoader.class, "j3m", "j3md");
        am.registerLoader(AWTLoader.class, "png", "jpg", "jpeg", "gif", "bmp");
        am.registerLoader(BinaryLoader.class, "j3o");
        return am;
    }

    /** Extracts the model's directory relative to the rsrc root from its dat path
     * (e.g. {@code props/frontier_town/buildings/saloon}). */
    protected static String modelTypePath (File datFile)
    {
        List<String> parts = new ArrayList<>();
        for (File dir = datFile.getParentFile();
                dir != null && !dir.getName().equals("rsrc"); dir = dir.getParentFile()) {
            parts.add(0, dir.getName());
        }
        return String.join("/", parts);
    }

    /** Collects per-geometry stats from a (re-imported) jME3 graph in traversal order. */
    protected static void collectStats (Spatial spatial, String path, List<GeoStats> stats)
    {
        String childPath =
            path.isEmpty() ? spatial.getName() : (path + "/" + spatial.getName());
        if (spatial instanceof Geometry geom) {
            String texture = null;
            if (geom.getMaterial().getTextureParam("DiffuseMap") != null) {
                texture = geom.getMaterial().getTextureParam("DiffuseMap").
                    getTextureValue().getKey().getName();
            }
            stats.add(new GeoStats(childPath, geom.getMesh().getVertexCount(),
                geom.getMesh().getTriangleCount(), texture));
        } else if (spatial instanceof Node node) {
            for (Spatial child : node.getChildren()) {
                collectStats(child, childPath, stats);
            }
        }
    }

    /** Compares expected (from the fork model) and actual (re-imported .j3o) stats. */
    protected static boolean verify (List<GeoStats> expected, List<GeoStats> actual)
    {
        System.out.println();
        System.out.println("Verification (fork model vs re-imported .j3o):");
        boolean ok = true;
        if (expected.size() != actual.size()) {
            System.out.println("  MISMATCH: geometry count " + expected.size() +
                " != " + actual.size());
            ok = false;
        }
        int totalVerts = 0, totalTris = 0;
        for (int ii = 0, nn = Math.min(expected.size(), actual.size()); ii < nn; ii++) {
            GeoStats exp = expected.get(ii), act = actual.get(ii);
            boolean match = exp.vertices() == act.vertices() &&
                exp.triangles() == act.triangles() &&
                java.util.Objects.equals(exp.texture(), act.texture());
            System.out.println("  " + (match ? "OK " : "MISMATCH ") + exp.path() +
                ": " + exp.vertices() + "v/" + exp.triangles() + "t/" + exp.texture() +
                (match ? "" : "  !=  " + act.vertices() + "v/" + act.triangles() +
                 "t/" + act.texture()));
            ok &= match;
            totalVerts += exp.vertices();
            totalTris += exp.triangles();
        }
        System.out.println("  " + expected.size() + " geometries, " + totalVerts +
            " vertices, " + totalTris + " triangles: " + (ok ? "PARITY OK" : "FAILED"));
        return ok;
    }

    /** Resolves material definitions, textures, and the exported .j3o. */
    protected final DesktopAssetManager _assetManager;

    /** The model's directory relative to the rsrc root. */
    protected final String _typePath;
}
