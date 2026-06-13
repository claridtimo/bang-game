//
// $Id$

package com.threerings.bang.tools.j3o;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.asset.ModelKey;
import com.jme3.asset.plugins.ClasspathLocator;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.export.binary.BinaryExporter;
import com.jme3.export.binary.BinaryLoader;
import com.jme3.material.plugins.J3MLoader;
import com.jme3.scene.Spatial;
import com.jme3.texture.plugins.AWTLoader;

import com.threerings.bang.jme3.model.BangModelLoader;
import com.threerings.bang.jme3.model.ModelConverter;

/**
 * Full-corpus verification harness for the runtime model loader. Walks every {@code model.dat}
 * under a resource root, loads each through {@link BangModelLoader}, exports + re-imports the
 * converted scene graph through the stock jME3 {@code BinaryImporter}, asserts per-geometry
 * mesh-stat parity, and reports static / animated / skinned coverage with real counts.
 *
 * <p>Usage: {@code VerifyCorpus <rsrc root> [<scratch out dir>]}. {@code <rsrc root>} is the
 * staged resource tree (e.g. {@code assets/build/staging/rsrc}) whose {@code .dat} files were
 * produced by the untouched {@code compileModels} build step.
 */
public class VerifyCorpus
{
    public static void main (String[] args)
        throws Exception
    {
        if (args.length < 1) {
            System.err.println("Usage: VerifyCorpus <rsrc root> [<scratch out dir>]");
            System.exit(1);
        }
        File rsrcRoot = new File(args[0]).getAbsoluteFile();
        File outDir = (args.length > 1)
            ? new File(args[1]).getAbsoluteFile()
            : Files.createTempDirectory("bang-j3o-verify").toFile();
        outDir.mkdirs();
        if (!rsrcRoot.isDirectory()) {
            System.err.println("rsrc root must exist: " + rsrcRoot);
            System.exit(1);
        }

        List<File> dats = new ArrayList<>();
        try (Stream<java.nio.file.Path> walk = Files.walk(rsrcRoot.toPath())) {
            walk.filter(p -> p.getFileName().toString().equals("model.dat"))
                .sorted().forEach(p -> dats.add(p.toFile()));
        }
        System.out.println("Found " + dats.size() + " model.dat under " + rsrcRoot);

        DesktopAssetManager am = new DesktopAssetManager();
        am.registerLocator("/", ClasspathLocator.class);
        am.registerLocator(rsrcRoot.getPath(), FileLocator.class);
        am.registerLocator(outDir.getPath(), FileLocator.class);
        am.registerLoader(J3MLoader.class, "j3m", "j3md");
        am.registerLoader(AWTLoader.class, "png", "jpg", "jpeg", "gif", "bmp");
        am.registerLoader(BinaryLoader.class, "j3o");

        int pass = 0, fail = 0;
        int staticCount = 0, animated = 0, skinned = 0, withControllers = 0, withSkipped = 0;
        int clampedModels = 0;
        long totalGeoms = 0, totalAnims = 0, totalSkinnedMeshes = 0;
        long totalJoints = 0, totalTracks = 0;
        List<String> failures = new ArrayList<>();

        for (File dat : dats) {
            String rel = rsrcRoot.toPath().relativize(dat.toPath()).toString();
            try {
                BangModelLoader loader = new BangModelLoader();
                Spatial converted = loader.loadModel(am, dat, null);
                ModelConverter.Result r = loader.lastResult();

                totalGeoms += r.geometries;
                totalAnims += r.animations.size();
                totalSkinnedMeshes += r.skinnedMeshes;
                totalJoints += r.armatureJoints;
                totalTracks += r.animTracks;
                if (r.isSkinned()) skinned++;
                if (r.isAnimated()) animated++;
                if (!r.isSkinned() && !r.isAnimated()) staticCount++;
                if (!r.droppedControllers.isEmpty()) withControllers++;
                if (!r.skippedSpatials.isEmpty()) withSkipped++;
                if (r.clampedInfluences) clampedModels++;

                // export + re-import for mesh-stat parity
                String j3oName = "verify-" + Integer.toHexString(rel.hashCode()) + ".j3o";
                File j3oFile = new File(outDir, j3oName);
                BinaryExporter.getInstance().save(converted, j3oFile);
                Spatial reloaded = am.loadModel(new ModelKey(j3oName));
                am.clearCache();

                List<ModelConverter.GeoStats> actual = new ArrayList<>();
                collectStats(reloaded, "", actual);
                String mismatch = compare(r.stats, actual);
                if (mismatch == null) {
                    pass++;
                } else {
                    fail++;
                    failures.add(rel + ": " + mismatch);
                }
                j3oFile.delete();
            } catch (Throwable t) {
                fail++;
                failures.add(rel + ": EXCEPTION " + t);
            }
        }

        System.out.println();
        System.out.println("=== Full-corpus parity + coverage ===");
        System.out.println("Models:            " + dats.size());
        System.out.println("Parity PASS:       " + pass);
        System.out.println("Parity FAIL:       " + fail);
        System.out.println("--- coverage (by model) ---");
        System.out.println("Fully static:      " + staticCount);
        System.out.println("Animated:          " + animated +
            "  (AnimClips on AnimComposer)");
        System.out.println("Skinned:           " + skinned +
            "  (Armature + SkinningControl)");
        System.out.println("With controllers:  " + withControllers +
            "  (detected, deferred to effects port)");
        System.out.println("With skipped geom: " + withSkipped +
            "  (ParticleMesh etc.)");
        System.out.println("Influence-clamped: " + clampedModels +
            "  (>4 bone influences on some vertex)");
        System.out.println("--- totals ---");
        System.out.println("Geometries:        " + totalGeoms);
        System.out.println("AnimClips:         " + totalAnims);
        System.out.println("TransformTracks:   " + totalTracks);
        System.out.println("Skinned meshes:    " + totalSkinnedMeshes);
        System.out.println("Armature joints:   " + totalJoints);
        if (!failures.isEmpty()) {
            System.out.println("--- failures ---");
            for (String f : failures) {
                System.out.println("  " + f);
            }
        }
        System.exit(fail == 0 ? 0 : 2);
    }

    /** Collects per-geometry stats from a (re-imported) jME3 graph in traversal order. */
    protected static void collectStats (
        Spatial spatial, String path, List<ModelConverter.GeoStats> stats)
    {
        String childPath =
            path.isEmpty() ? spatial.getName() : (path + "/" + spatial.getName());
        if (spatial instanceof com.jme3.scene.Geometry geom) {
            String texture = null;
            if (geom.getMaterial().getTextureParam("DiffuseMap") != null) {
                texture = geom.getMaterial().getTextureParam("DiffuseMap").
                    getTextureValue().getKey().getName();
            }
            stats.add(new ModelConverter.GeoStats(childPath, geom.getMesh().getVertexCount(),
                geom.getMesh().getTriangleCount(), texture));
        } else if (spatial instanceof com.jme3.scene.Node node) {
            for (Spatial child : node.getChildren()) {
                collectStats(child, childPath, stats);
            }
        }
    }

    /** Compares expected vs actual geo stats; returns null if parity holds, else a message. */
    protected static String compare (
        List<ModelConverter.GeoStats> expected, List<ModelConverter.GeoStats> actual)
    {
        if (expected.size() != actual.size()) {
            return "geometry count " + expected.size() + " != " + actual.size();
        }
        for (int ii = 0; ii < expected.size(); ii++) {
            ModelConverter.GeoStats e = expected.get(ii), a = actual.get(ii);
            if (e.vertices() != a.vertices() || e.triangles() != a.triangles()
                    || !java.util.Objects.equals(e.texture(), a.texture())) {
                return "geom " + e.path() + " " + e.vertices() + "v/" + e.triangles() + "t/" +
                    e.texture() + " != " + a.vertices() + "v/" + a.triangles() + "t/" + a.texture();
            }
        }
        return null;
    }
}
