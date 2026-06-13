//
// $Id$

package com.threerings.bang.tools.j3o;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.bounding.BoundingBox;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.system.AppSettings;
import com.jme3.system.JmeContext;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;
import com.jme3.util.Screenshots;

import com.threerings.jme.model.Model;
import com.threerings.jme.model.ModelTextureResolver;
import com.threerings.jme.model.TextureProvider;

/**
 * Offscreen render-to-PNG harness for a single baked model — the headless visual-inspection
 * tool the cutover plan flags as the highest-value Phase-7 idea (a scriptable, deterministic
 * snapshot of a model/scene that an agent can diff against a baseline, replacing flaky
 * X-display grabs).
 *
 * <p>It loads one baked {@code model.j3o} <em>through the real runtime path</em> — the app-side
 * {@link Model} facade + {@link ModelTextureResolver} re-resolution + a {@link Model#createInstance}
 * clone, exactly what {@code ModelCache.loadPrototype}/{@code createInstance} do in the live client
 * — then lights it the way {@code BoardView} does (one white directional key light + a soft
 * ambient), frames it, renders one frame to an offscreen {@link FrameBuffer} with no visible
 * window, and writes the result to a PNG. So a model's textures, materials and lighting can be
 * inspected in isolation, off the live game flow.
 *
 * <p>This is the harness used to confirm the building models bake and resolve to fully-textured
 * geometry (each building sub-mesh carries its own resolved {@code DiffuseMap}); see
 * {@code docs/jme3-cutover-plan.md} Phase&nbsp;4.
 *
 * <p>Usage: {@code RenderToPng <rsrc root> <model type path> <output png>}, e.g.
 * {@code RenderToPng .../staging/rsrc props/frontier_town/buildings/saloon /tmp/saloon.png}.
 */
public class RenderToPng extends SimpleApplication
{
    public static void main (String[] args)
    {
        if (args.length != 3) {
            System.err.println("Usage: RenderToPng <rsrc root> <model type path> <output png>");
            System.exit(1);
        }
        RenderToPng app = new RenderToPng(args[0], args[1], args[2]);
        app.setShowSettings(false);
        AppSettings settings = new AppSettings(true);
        settings.setWidth(WIDTH);
        settings.setHeight(HEIGHT);
        settings.setSamples(4);
        app.setSettings(settings);
        app.setPauseOnLostFocus(false);
        // a real GL context is needed to rasterise; LWJGL3 gives us a hidden offscreen window
        app.start(JmeContext.Type.OffscreenSurface);
    }

    public RenderToPng (String rsrcRoot, String modelType, String outPng)
    {
        _rsrcRoot = new File(rsrcRoot).getAbsoluteFile();
        _modelType = modelType;
        _outPng = new File(outPng).getAbsoluteFile();
    }

    @Override public void simpleInitApp ()
    {
        assetManager.registerLocator(_rsrcRoot.getPath(), FileLocator.class);

        // load through the real runtime path: facade-wrap the .j3o, re-resolve textures, clone.
        Spatial model = loadInstance();

        Node scene = new Node("scene");
        scene.attachChild(model);

        // light it like BoardView: a single white directional key light + a soft ambient term
        // (the default board's light 0 is white directional, its ambient a dark grey).
        DirectionalLight dl = new DirectionalLight();
        dl.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
        dl.setColor(ColorRGBA.White);
        scene.addLight(dl);
        AmbientLight al = new AmbientLight();
        al.setColor(new ColorRGBA(0.35f, 0.35f, 0.35f, 1f));
        scene.addLight(al);
        scene.updateGeometricState();

        // frame the model: aim a fresh camera at its bounding box from a 3/4 angle
        BoundingBox bb = (BoundingBox)model.getWorldBound();
        Vector3f center = bb.getCenter();
        float radius = Math.max(bb.getXExtent(), Math.max(bb.getYExtent(), bb.getZExtent())) * 2.4f;
        Vector3f eye = center.add(new Vector3f(radius, radius * 0.8f, radius));
        Camera offCam = new Camera(WIDTH, HEIGHT);
        offCam.setFrustumPerspective(45f, (float)WIDTH / HEIGHT, 0.1f, radius * 10f);
        offCam.setLocation(eye);
        offCam.lookAt(center, Vector3f.UNIT_Y);

        // render the scene into our own offscreen FrameBuffer backed by a color texture, then
        // read that back — robust on an OffscreenSurface context where the default framebuffer
        // read is unreliable.
        _fb = new FrameBuffer(WIDTH, HEIGHT, 1);
        _colorTex = new Texture2D(WIDTH, HEIGHT, Image.Format.RGBA8);
        _fb.setDepthBuffer(Image.Format.Depth);
        _fb.setColorTexture(_colorTex);

        ViewPort off = renderManager.createMainView("offscreen", offCam);
        off.setClearFlags(true, true, true);
        off.setBackgroundColor(new ColorRGBA(0.45f, 0.5f, 0.55f, 1f));
        off.setOutputFrameBuffer(_fb);
        off.attachScene(scene);
        off.addProcessor(new CaptureProcessor());

        System.out.println("RenderToPng: " + _modelType + " geometries:");
        reportGeoms(model, "  ");
    }

    /**
     * Loads the model through the real app-side {@link Model} facade + {@link ModelTextureResolver},
     * reproducing the runtime {@code ModelCache.loadPrototype}/{@code createInstance} flow exactly,
     * so the harness renders what the live client renders. Returns the cloned instance.
     */
    protected Spatial loadInstance ()
    {
        Spatial content = assetManager.loadModel(_modelType + "/model.j3o");
        Model proto = new Model(content);
        final String typePath = _modelType;
        proto.resolveTextures(new TextureProvider() {
            private final ModelTextureResolver _r = new ModelTextureResolver(assetManager);
            public Texture getTexture (Geometry geom, String name) {
                String path = _r.textureAssetPath(typePath, name);
                try {
                    return assetManager.loadTexture(path);
                } catch (RuntimeException e) {
                    System.out.println("  getTexture FAILED " + path + " : " +
                        e.getClass().getSimpleName());
                    return null;
                }
            }
        });
        return proto.createInstance();
    }

    protected void reportGeoms (Spatial s, String ind)
    {
        if (s instanceof Geometry g) {
            boolean diffuse = g.getMaterial().getTextureParam("DiffuseMap") != null;
            boolean normals = g.getMesh().getBuffer(VertexBuffer.Type.Normal) != null;
            FaceCullMode cull = g.getMaterial().getAdditionalRenderState().getFaceCullMode();
            System.out.println(ind + g.getName() + " diffuse=" + diffuse +
                " normals=" + normals + " cull=" + cull +
                " bucket=" + g.getLocalQueueBucket());
        } else if (s instanceof Node n) {
            for (Spatial c : n.getChildren()) {
                reportGeoms(c, ind);
            }
        }
    }

    /** Reads the rendered framebuffer back to a PNG once the first frame is drawn. */
    protected class CaptureProcessor implements SceneProcessor
    {
        @Override public void initialize (RenderManager rm, ViewPort vp) { _rm = rm; }
        @Override public void reshape (ViewPort vp, int w, int h) { }
        @Override public boolean isInitialized () { return _rm != null; }
        @Override public void preFrame (float tpf) { }
        @Override public void postQueue (RenderQueue rq) { }
        @Override public void setProfiler (AppProfiler profiler) { }
        @Override public void cleanup () { }

        @Override public void postFrame (FrameBuffer out)
        {
            if (_captured) {
                return;
            }
            _captured = true;
            ByteBuffer buf = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
            renderer.readFrameBuffer(_fb, buf);
            BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_4BYTE_ABGR);
            Screenshots.convertScreenShot(buf, img);
            try {
                ImageIO.write(img, "png", _outPng);
                System.out.println("Wrote " + _outPng);
            } catch (IOException e) {
                e.printStackTrace();
            }
            // stop after one frame
            enqueue(() -> { stop(); return null; });
        }

        private RenderManager _rm;
        private boolean _captured;
    }

    protected final File _rsrcRoot;
    protected final String _modelType;
    protected final File _outPng;

    protected FrameBuffer _fb;
    protected Texture2D _colorTex;

    protected static final int WIDTH = 800;
    protected static final int HEIGHT = 600;
}
