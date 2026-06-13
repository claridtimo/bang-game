//
// $Id$

package com.threerings.bang.jme3.model;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import com.jme3.anim.AnimClip;
import com.jme3.anim.AnimComposer;
import com.jme3.anim.AnimTrack;
import com.jme3.anim.Armature;
import com.jme3.anim.Joint;
import com.jme3.anim.SkinningControl;
import com.jme3.anim.TransformTrack;
import com.jme3.anim.util.HasLocalTransform;
import com.jme3.material.MatParamTexture;
import com.jme3.material.Material;
import com.jme3.material.RenderState.FaceCullMode;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue.Bucket;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import com.threerings.jme.model.Model;
import com.threerings.jme.model.ModelAnimAccess;
import com.threerings.jme.model.ModelMesh;
import com.threerings.jme.model.ModelMeshAccess;
import com.threerings.jme.model.ModelNode;
import com.threerings.jme.model.SkinMesh;

/**
 * Converts a loaded fork {@link Model} (the vendored jME-fork scenegraph deserialized from
 * a {@code model.dat}) into a jMonkeyEngine&nbsp;3 {@link Spatial} tree.
 *
 * <p>This is the shared conversion engine used both by the runtime {@link BangModelLoader}
 * (an {@code AssetManager} loader) and by the {@code ModelToJ3o} build-time exporter. It is
 * deliberately self-contained and headless: it never touches GL state, so it runs against a
 * {@code DummyDisplaySystem} just like the model compiler.
 *
 * <p>What it converts:
 * <ul>
 *   <li><b>Geometry</b> — position/normal/single-UV/index buffers copied straight from each
 *       {@link ModelMesh} (the fork already Forsyth-optimised them).</li>
 *   <li><b>Hierarchy + transforms</b> — every {@link ModelNode} becomes a jME3 {@link Node}
 *       carrying the fork local TRS.</li>
 *   <li><b>Materials/textures</b> — a placeholder {@code Lighting.j3md} material with the
 *       default (first) texture resolved through {@link ModelTextureResolver}; the full
 *       multi-valued texture list and render flags are preserved (see that class).</li>
 *   <li><b>Rigid keyframe animation</b> — each fork {@link Model.Animation} becomes one
 *       {@link AnimClip} of {@link TransformTrack}s targeting the moving {@link Node}s, on an
 *       {@link AnimComposer} attached to the root.</li>
 *   <li><b>Skinning</b> — {@link SkinMesh} weight groups become 4-influence
 *       {@code BoneIndex}/{@code BoneWeight} buffers over an {@link Armature} of {@link Joint}s
 *       (the referenced bone {@link ModelNode}s), driven by a {@link SkinningControl}; the same
 *       animation clips drive the joints.</li>
 * </ul>
 *
 * <p>What it reports but does not convert: procedural/emission controllers (deferred to the
 * effects port) and non-mesh procedural geometry (e.g. {@code ParticleMesh}).
 */
public class ModelConverter
{
    /** Per-geometry statistics used for parity verification. */
    /**
     * Per-geometry fidelity snapshot used for round-trip parity. Beyond mesh counts it captures
     * the material/skinning state that survives the {@code .j3o} round trip, so a dropped glow
     * map, flipped cull/blend flag, lost transparent bucket, or missing skinning buffer is caught
     * (the earlier count-and-base-texture-only check passed all of those silently).
     */
    public record GeoStats (String path, int vertices, int triangles, String texture,
                            String glowTexture, String blend, boolean cullOff,
                            boolean transparentBucket, int maxWeights) { }

    /** Extracts a {@link GeoStats} from a jME3 geometry — used identically on the freshly
     * converted graph and on the re-imported {@code .j3o}, so parity compares like with like. */
    public static GeoStats statsOf (String path, Geometry geom)
    {
        Mesh m = geom.getMesh();
        Material mat = geom.getMaterial();
        return new GeoStats(path, m.getVertexCount(), m.getTriangleCount(),
            texName(mat, "DiffuseMap"), texName(mat, "GlowMap"),
            mat.getAdditionalRenderState().getBlendMode().name(),
            mat.getAdditionalRenderState().getFaceCullMode() == FaceCullMode.Off,
            geom.getLocalQueueBucket() == Bucket.Transparent,
            m.getMaxNumWeights());
    }

    private static String texName (Material mat, String param)
    {
        MatParamTexture p = mat.getTextureParam(param);
        return (p == null || p.getTextureValue() == null || p.getTextureValue().getKey() == null)
            ? null : p.getTextureValue().getKey().getName();
    }

    /** The outcome of one conversion, with everything a caller needs to verify coverage. */
    public static class Result
    {
        /** The converted jME3 scene graph root. */
        public Node root;

        /** Per-geometry stats in traversal order (for parity checks). */
        public final List<GeoStats> stats = new ArrayList<>();

        /** Names of animations converted to {@link AnimClip}s. */
        public final List<String> animations = new ArrayList<>();

        /** Animations present in the model but NOT converted (no transforms / no live target). */
        public final List<String> droppedAnimations = new ArrayList<>();

        /** Number of {@link SkinMesh}es converted with full skinning. */
        public int skinnedMeshes;

        /** Number of geometries in the model (rigid + skinned + static). */
        public int geometries;

        /** Controller class names detected and NOT converted (deferred to effects port). */
        public final List<String> droppedControllers = new ArrayList<>();

        /** Non-mesh procedural spatials skipped (e.g. ParticleMesh). */
        public final List<String> skippedSpatials = new ArrayList<>();

        /** True if any SkinMesh vertex needed >4 influences (clamped + renormalised). */
        public boolean clampedInfluences;

        /** Number of joints in the converted armature (0 if not skinned). */
        public int armatureJoints;

        /** Total TransformTracks across all converted AnimClips. */
        public int animTracks;

        public boolean isAnimated () { return !animations.isEmpty(); }
        public boolean isSkinned () { return skinnedMeshes > 0; }
    }

    /**
     * @param resolver resolves stored texture names to jME3 assets (the ModelCache-equivalent).
     * @param typePath the model's directory relative to the rsrc root (e.g.
     * {@code units/frontier_town/gunslinger}); used to resolve relative texture names.
     */
    public ModelConverter (ModelTextureResolver resolver, String typePath)
    {
        _resolver = resolver;
        _typePath = typePath;
    }

    /**
     * Converts the supplied fork model into a jME3 scene graph, returning a {@link Result}
     * describing exactly what was and was not converted.
     */
    public Result convert (Model model)
    {
        Result result = new Result();

        // 1. identify the bone nodes (those referenced by any SkinMesh weight group) so we
        // know which ModelNodes also need a Joint mirror in the armature.
        collectBoneNodes(model);

        // 2. build the scene-graph Node/Geometry tree, recording the fork-node -> jME3-node
        // mapping so animation tracks and skinning can resolve their targets.
        result.root = (Node)convertSpatial(model, "", result);

        // 3. build the armature (if any bones) and skinning controls.
        if (!_boneNodes.isEmpty()) {
            buildArmature();
            applySkinning(result);
            if (_armature != null) {
                result.armatureJoints = _armature.getJointCount();
            }
        }

        // 4. convert animations to AnimClips on an AnimComposer.
        convertAnimations(model, result);

        // 5. detect (but do not convert) procedural controllers.
        for (Object ctrl : model.getControllers()) {
            result.droppedControllers.add(ctrl.getClass().getName());
        }

        // 6. preserve fork-only metadata as user data (round-trip safety / runtime sprites).
        Properties props = model.getProperties();
        StringBuilder buf = new StringBuilder();
        for (String key : props.stringPropertyNames().stream().sorted().toList()) {
            buf.append(key).append(" = ").append(props.getProperty(key)).append('\n');
        }
        result.root.setUserData("bang.properties", buf.toString());
        result.root.setUserData("bang.animations", String.join(",", model.getAnimationNames()));
        result.root.setUserData("bang.type", _typePath);

        return result;
    }

    /** Walks the model gathering every ModelNode used as a skinning bone. */
    protected void collectBoneNodes (com.jme.scene.Spatial spatial)
    {
        if (spatial instanceof SkinMesh skin) {
            SkinMesh.WeightGroup[] groups = ModelMeshAccess.weightGroups(skin);
            if (groups != null) {
                for (SkinMesh.WeightGroup group : groups) {
                    for (SkinMesh.Bone bone : group.bones) {
                        if (bone != null && bone.node != null) {
                            _boneNodes.put(bone.node, Boolean.TRUE);
                        }
                    }
                }
            }
        } else if (spatial instanceof com.jme.scene.Node node) {
            for (int ii = 0, nn = node.getQuantity(); ii < nn; ii++) {
                collectBoneNodes(node.getChild(ii));
            }
        }
    }

    /** Recursively converts a fork spatial to jME3, recording the node mapping. */
    protected Spatial convertSpatial (
        com.jme.scene.Spatial spatial, String path, Result result)
    {
        String name = spatial.getName();
        String childPath = path.isEmpty() ? name : (path + "/" + name);
        Spatial converted;
        if (spatial instanceof ModelMesh mesh) {
            converted = convertMesh(mesh, childPath, result);
        } else if (spatial instanceof com.jme.scene.Node fnode) {
            Node node = new Node(name);
            for (int ii = 0, nn = fnode.getQuantity(); ii < nn; ii++) {
                Spatial child = convertSpatial(fnode.getChild(ii), childPath, result);
                if (child != null) {
                    node.attachChild(child);
                }
            }
            converted = node;
        } else {
            // procedural effect geometry (ParticleMesh, etc.); reproduced at runtime from
            // the model's controllers, not stored as convertible mesh data
            result.skippedSpatials.add(childPath + " (" + spatial.getClass().getName() + ")");
            return null;
        }
        copyTransform(spatial, converted);
        _nodeMap.put(spatial, converted);
        return converted;
    }

    /** Converts one fork mesh to a jME3 Geometry with a placeholder lit material. */
    protected Geometry convertMesh (ModelMesh fmesh, String path, Result result)
    {
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
        mesh.setBuffer(VertexBuffer.Type.Index, 3, copy(fmesh.getIndexBuffer(0)));

        boolean skinned = false;
        if (fmesh instanceof SkinMesh skin) {
            skinned = addSkinningBuffers(skin, mesh, result);
        }

        mesh.updateBound();

        Geometry geom = new Geometry(fmesh.getName(), mesh);

        // material: placeholder Lighting.j3md, textures resolved via the ModelCache-equivalent
        String[] textures = ModelMeshAccess.textures(fmesh);
        _resolver.applyMaterial(
            geom, _typePath, ModelMeshAccess.textureKey(fmesh), textures,
            ModelMeshAccess.solid(fmesh), ModelMeshAccess.additive(fmesh),
            ModelMeshAccess.transparent(fmesh), ModelMeshAccess.alphaThreshold(fmesh),
            ModelMeshAccess.emissive(fmesh), ModelMeshAccess.emissiveMap(fmesh),
            ModelMeshAccess.sphereMapped(fmesh));

        result.geometries++;
        result.stats.add(statsOf(path, geom));
        if (skinned) {
            result.skinnedMeshes++;
            _skinnedMeshes.add(geom);
        }
        return geom;
    }

    /**
     * Builds the 4-influence {@code BoneIndex}/{@code BoneWeight} buffers for a skin mesh from
     * the fork's variable-width weight groups, and registers the mesh's bone-index ordering so
     * the armature can be wired. Returns true if the mesh was given skinning buffers.
     *
     * <p>The fork stores vertices pre-sorted into contiguous runs (weight groups), each group
     * naming its own bone set with interleaved weights. The deformer walks groups in order and
     * the vertex buffer is laid out in that same order, so weight-group vertex N maps to mesh
     * vertex N linearly. We expand each group's variable bone count into jME3's fixed 4 slots
     * (keeping the 4 highest weights, renormalised) and index into a per-mesh global bone list.
     */
    protected boolean addSkinningBuffers (SkinMesh skin, Mesh mesh, Result result)
    {
        SkinMesh.WeightGroup[] groups = ModelMeshAccess.weightGroups(skin);
        if (groups == null || groups.length == 0) {
            return false;
        }
        int vertexCount = mesh.getVertexCount();

        // per-mesh ordered bone list (ModelNode -> local index), captured for armature wiring
        Map<ModelNode, Integer> boneIndex = new LinkedHashMap<>();
        List<ModelNode> boneOrder = new ArrayList<>();

        // UnsignedShort (not UnsignedByte) so armatures with >255 joints don't wrap mod 256;
        // current content tops out ~31 bones/mesh, but jME3 supports short bone indices natively
        ShortBuffer indices = BufferUtils.createShortBuffer(vertexCount * 4);
        FloatBuffer weights = BufferUtils.createFloatBuffer(vertexCount * 4);

        int[] oi = new int[4];   // top-4 index/weight scratch, reused across all vertices
        float[] ow = new float[4];

        int vert = 0;
        for (SkinMesh.WeightGroup group : groups) {
            int gbones = group.bones.length;
            // a group's bone set (and thus its global bone indices) is constant for every
            // vertex in the group, so resolve it once instead of per vertex
            int[] gi = new int[gbones];
            for (int bb = 0; bb < gbones; bb++) {
                // mirror collectBoneNodes' guard: ModelDef.resolveReferences can leave a
                // Bone (or its node) null for an unresolved bone name; pin to joint 0
                // rather than NPEing or polluting the bone order with null
                SkinMesh.Bone bone = group.bones[bb];
                ModelNode node = (bone != null) ? bone.node : null;
                if (node == null) {
                    gi[bb] = 0;
                    continue;
                }
                Integer idx = boneIndex.get(node);
                if (idx == null) {
                    idx = boneOrder.size();
                    boneIndex.put(node, idx);
                    boneOrder.add(node);
                }
                gi[bb] = idx;
            }
            float[] gw = new float[gbones];      // scratch, refilled per vertex in this group
            boolean[] used = new boolean[gbones]; // selection-sort scratch, reused per vertex
            for (int vv = 0; vv < group.vertexCount; vv++, vert++) {
                for (int bb = 0; bb < gbones; bb++) {
                    gw[bb] = group.weights[vv * gbones + bb];
                }
                writeVertexInfluences(gi, gw, used, oi, ow, indices, weights, result);
            }
        }

        // jME3 wants weights normalised and a declared max influence count
        indices.flip();
        weights.flip();
        mesh.setMaxNumWeights(4);

        VertexBuffer ib = new VertexBuffer(VertexBuffer.Type.BoneIndex);
        ib.setupData(VertexBuffer.Usage.CpuOnly, 4, VertexBuffer.Format.UnsignedShort, indices);
        mesh.setBuffer(ib);
        VertexBuffer wb = new VertexBuffer(VertexBuffer.Type.BoneWeight);
        wb.setupData(VertexBuffer.Usage.CpuOnly, 4, VertexBuffer.Format.Float, weights);
        mesh.setBuffer(wb);

        _meshBoneOrder.put(mesh, boneOrder);
        return true;
    }

    /**
     * Reduces a vertex's variable-width influences to jME3's fixed 4 (highest weights,
     * renormalised) and appends them to the index/weight buffers.
     */
    protected void writeVertexInfluences (
        int[] gi, float[] gw, boolean[] used, int[] oi, float[] ow,
        ShortBuffer indices, FloatBuffer weights, Result result)
    {
        int n = gi.length;
        // selection sort the top-4 by descending weight (n is tiny)
        int keep = Math.min(4, n);
        if (n > 4) {
            result.clampedInfluences = true;
        }
        java.util.Arrays.fill(used, 0, n, false);
        float total = 0f;
        for (int slot = 0; slot < keep; slot++) {
            int best = -1;
            float bestW = -1f;
            for (int kk = 0; kk < n; kk++) {
                if (!used[kk] && gw[kk] > bestW) {
                    bestW = gw[kk];
                    best = kk;
                }
            }
            used[best] = true;
            oi[slot] = gi[best];
            ow[slot] = gw[best];
            total += gw[best];
        }
        // renormalise so the 4 (or fewer) kept weights sum to 1
        if (total <= 0f) {
            ow[0] = 1f; // degenerate: pin to first bone
            total = 1f;
        }
        for (int slot = 0; slot < 4; slot++) {
            // unused slots are written explicitly (oi/ow are reused scratch and may be stale)
            indices.put(slot < keep ? (short)(oi[slot] & 0xFFFF) : (short)0);
            weights.put(slot < keep ? ow[slot] / total : 0f);
        }
    }

    /**
     * Builds a jME3 {@link Armature} from the collected bone nodes, mirroring the fork node
     * hierarchy among the bones (a bone's jME3 parent is its nearest bone ancestor).
     */
    protected void buildArmature ()
    {
        // create a Joint per bone node, carrying the fork local transform
        for (ModelNode bnode : _boneNodes.keySet()) {
            Joint joint = new Joint(uniqueJointName(bnode.getName()));
            com.jme.math.Vector3f t = bnode.getLocalTranslation();
            com.jme.math.Quaternion r = bnode.getLocalRotation();
            com.jme.math.Vector3f s = bnode.getLocalScale();
            joint.setLocalTransform(new com.jme3.math.Transform(
                new Vector3f(t.x, t.y, t.z),
                new Quaternion(r.x, r.y, r.z, r.w),
                new Vector3f(s.x, s.y, s.z)));
            _joints.put(bnode, joint);
        }
        // parent each joint under its nearest bone ancestor; roots (no bone ancestor) stay
        // top-level — the Armature derives its roots from the joint parent links itself
        for (Map.Entry<ModelNode, Joint> e : _joints.entrySet()) {
            ModelNode parent = nearestBoneAncestor(e.getKey());
            if (parent != null) {
                _joints.get(parent).addChild(e.getValue());
            }
        }
        List<Joint> all = new ArrayList<>(_joints.values());
        _armature = new Armature(all.toArray(new Joint[0]));
        _armature.saveInitialPose();
    }

    /** Returns the nearest ancestor ModelNode of {@code node} that is itself a bone. */
    protected ModelNode nearestBoneAncestor (ModelNode node)
    {
        for (com.jme.scene.Spatial p = node.getParent(); p != null; p = p.getParent()) {
            if (p instanceof ModelNode mn && _boneNodes.containsKey(mn)) {
                return mn;
            }
        }
        return null;
    }

    /** Attaches a SkinningControl with the armature to each skinned mesh's geometry. */
    protected void applySkinning (Result result)
    {
        if (_armature == null) {
            return;
        }
        for (Geometry geom : _skinnedMeshes) {
            List<ModelNode> order = _meshBoneOrder.get(geom.getMesh());
            if (order == null) {
                continue;
            }
            // remap the mesh's local bone indices to armature joint indices
            remapBoneIndices(geom.getMesh(), order);
            geom.getMesh().generateBindPose();
        }
        // a single SkinningControl on the root drives all skinned geometries beneath it
        SkinningControl skinning = new SkinningControl(_armature);
        _skinningControl = skinning;
    }

    /** Rewrites a mesh's BoneIndex buffer from per-mesh bone order to armature joint indices. */
    protected void remapBoneIndices (Mesh mesh, List<ModelNode> order)
    {
        List<Joint> jointList = _armature.getJointList();
        Map<Joint, Integer> jointIndex = new IdentityHashMap<>();
        for (int ii = 0; ii < jointList.size(); ii++) {
            jointIndex.put(jointList.get(ii), ii);
        }
        int[] remap = new int[order.size()];
        for (int ii = 0; ii < order.size(); ii++) {
            Joint j = _joints.get(order.get(ii));
            remap[ii] = (j != null) ? jointIndex.get(j) : 0;
        }
        VertexBuffer ib = mesh.getBuffer(VertexBuffer.Type.BoneIndex);
        ShortBuffer data = (ShortBuffer)ib.getData();
        data.clear();
        for (int ii = 0; ii < data.capacity(); ii++) {
            int local = data.get(ii) & 0xFFFF;
            data.put(ii, (short)(remap[local] & 0xFFFF));
        }
        ib.updateData(data);
    }

    /**
     * Converts every fork {@link Model.Animation} into a jME3 {@link AnimClip} of
     * {@link TransformTrack}s, attaching an {@link AnimComposer} to the root (and a
     * {@link SkinningControl} if the model is skinned).
     */
    protected void convertAnimations (Model model, Result result)
    {
        String[] names = model.getAnimationNames();
        AnimComposer composer = null;
        StringBuilder loopModes = new StringBuilder();
        for (String name : names) {
            Model.Animation anim = model.getAnimation(name);
            if (anim == null || anim.transforms == null || anim.transformTargets == null) {
                result.droppedAnimations.add(name); // no keyframe data to convert
                continue;
            }
            AnimClip clip = buildClip(name, anim);
            if (clip == null) {
                result.droppedAnimations.add(name); // no track resolved to a converted target
                continue;
            }
            if (composer == null) {
                composer = new AnimComposer();
            }
            composer.addAnimClip(clip);
            result.animations.add(name);
            result.animTracks += clip.getTracks().length;
            // jME3 carries loop mode on the playback action, not the clip; preserve the fork's
            // repeatType as user data so the cutover can set LoopMode when it builds actions
            if (loopModes.length() > 0) {
                loopModes.append(',');
            }
            loopModes.append(name).append('=').append(loopMode(anim.repeatType));
        }
        if (_skinningControl != null) {
            result.root.addControl(_skinningControl);
        }
        if (composer != null) {
            result.root.addControl(composer);
            result.root.setUserData("bang.loopModes", loopModes.toString());
        }
    }

    /** Maps the fork {@code Controller} repeat type to the jME3 {@code LoopMode} name. */
    protected static String loopMode (int repeatType)
    {
        switch (repeatType) {
        case com.jme.scene.Controller.RT_WRAP:  return "Loop";
        case com.jme.scene.Controller.RT_CYCLE: return "Cycle";
        default:                                return "DontLoop"; // RT_CLAMP
        }
    }

    /** Builds one AnimClip from a fork Animation's packed per-frame TRS keyframes. */
    protected AnimClip buildClip (String name, Model.Animation anim)
    {
        int frames = anim.transforms.length;
        if (frames == 0) {
            return null;
        }
        int fps = anim.frameRate > 0 ? anim.frameRate : 30;
        float[] times = new float[frames];
        for (int ff = 0; ff < frames; ff++) {
            times[ff] = (float)ff / fps;
        }

        List<AnimTrack<?>> tracks = new ArrayList<>();
        com.jme.scene.Spatial[] targets = anim.transformTargets;
        for (int tt = 0; tt < targets.length; tt++) {
            HasLocalTransform target = resolveTrackTarget(targets[tt]);
            if (target == null) {
                continue; // target wasn't converted (e.g. under a skipped ParticleMesh)
            }
            Vector3f[] trans = new Vector3f[frames];
            Quaternion[] rots = new Quaternion[frames];
            Vector3f[] scales = new Vector3f[frames];
            for (int ff = 0; ff < frames; ff++) {
                Model.Transform xf = anim.transforms[ff][tt];
                com.jme.math.Vector3f t = ModelAnimAccess.translation(xf);
                com.jme.math.Quaternion r = ModelAnimAccess.rotation(xf);
                com.jme.math.Vector3f s = ModelAnimAccess.scale(xf);
                trans[ff] = new Vector3f(t.x, t.y, t.z);
                rots[ff] = new Quaternion(r.x, r.y, r.z, r.w);
                scales[ff] = new Vector3f(s.x, s.y, s.z);
            }
            tracks.add(new TransformTrack(target, times, trans, rots, scales));
        }
        if (tracks.isEmpty()) {
            return null;
        }
        AnimClip clip = new AnimClip(name);
        clip.setTracks(tracks.toArray(new AnimTrack<?>[0]));
        return clip;
    }

    /**
     * Resolves an animation target (a fork Spatial) to its jME3 track target: a {@link Joint}
     * if the node is a skinning bone, otherwise the converted {@link Node} (rigid animation).
     */
    protected HasLocalTransform resolveTrackTarget (com.jme.scene.Spatial forkTarget)
    {
        if (forkTarget instanceof ModelNode mn && _joints.containsKey(mn)) {
            return _joints.get(mn);
        }
        return _nodeMap.get(forkTarget);
    }

    protected String uniqueJointName (String base)
    {
        String name = base != null ? base : "joint";
        String candidate = name;
        int suffix = 1;
        while (_usedJointNames.contains(candidate)) {
            candidate = name + "$" + (suffix++);
        }
        _usedJointNames.add(candidate);
        return candidate;
    }

    /** Copies the local transform from a fork spatial to a jME3 spatial. */
    protected static void copyTransform (com.jme.scene.Spatial from, Spatial to)
    {
        com.jme.math.Vector3f t = from.getLocalTranslation();
        com.jme.math.Quaternion r = from.getLocalRotation();
        com.jme.math.Vector3f s = from.getLocalScale();
        to.setLocalTranslation(t.x, t.y, t.z);
        to.setLocalRotation(new Quaternion(r.x, r.y, r.z, r.w));
        to.setLocalScale(s.x, s.y, s.z);
    }

    /** Defensive copy of a fork float buffer into a fresh jME3-owned buffer. The fork's live
     * buffers must not be aliased into the jME3 mesh: a runtime loader may load a model more
     * than once, and the fork model can be read again afterward — sharing (and clear()-ing)
     * its buffers would corrupt those readers. */
    protected static FloatBuffer prepare (FloatBuffer buf)
    {
        buf.clear();
        FloatBuffer out = BufferUtils.createFloatBuffer(buf.remaining());
        out.put(buf);
        out.flip();
        return out;
    }

    /** Defensive copy of a fork index buffer (see {@link #prepare}). */
    protected static IntBuffer copy (IntBuffer buf)
    {
        buf.clear();
        IntBuffer out = BufferUtils.createIntBuffer(buf.remaining());
        out.put(buf);
        out.flip();
        return out;
    }

    protected final ModelTextureResolver _resolver;
    protected final String _typePath;

    /** Maps each fork spatial to its converted jME3 spatial (for anim target resolution). */
    protected final Map<com.jme.scene.Spatial, Spatial> _nodeMap = new IdentityHashMap<>();

    /** The set of ModelNodes used as skinning bones (identity set). */
    protected final Map<ModelNode, Boolean> _boneNodes = new IdentityHashMap<>();

    /** Maps each bone ModelNode to its jME3 Joint. */
    protected final Map<ModelNode, Joint> _joints = new LinkedHashMap<>();

    /** Per-mesh ordered bone list captured during buffer building, for armature remap. */
    protected final Map<Mesh, List<ModelNode>> _meshBoneOrder = new IdentityHashMap<>();

    /** Converted geometries backed by SkinMeshes. */
    protected final List<Geometry> _skinnedMeshes = new ArrayList<>();

    protected final java.util.Set<String> _usedJointNames = new java.util.HashSet<>();

    protected Armature _armature;
    protected SkinningControl _skinningControl;
}
