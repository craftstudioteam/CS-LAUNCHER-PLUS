package net.kdt.pojavlaunch.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;

import androidx.annotation.NonNull;

import net.kdt.pojavlaunch.yggdrasil.SkinAnalyzer;
import net.kdt.pojavlaunch.yggdrasil.SkinModelType;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  Shared 3D Minecraft skin renderer (GLES20) — THE one renderer used by both
 *  the Skin Management page and the Home screen player.
 *
 *  Extracted verbatim from SkinManagerFragment's working SkinRenderer (the
 *  reference implementation users see rendering correctly), plus small
 *  OPTIONAL animation hooks so Home can idle-sway / wave without touching the
 *  default behavior (all pose angles default to 0 = exactly the management
 *  page's look):
 *      {@link #mPoseLeftArmZ} / {@link #mPoseRightArmZ}  — shoulder swing (rad)
 *      {@link #mPoseHeadYaw}                            — head glance (rad)
 *      {@link #mRotateSpeed}                            — auto-rotate deg/frame
 *
 *  Model/UV: canonical 64×64 layout (legacy 64×32 skins expanded with mirrored
 *  left limbs), Classic + Slim geometry, overlay layers (hat/jacket/sleeves/
 *  pants + cape) drawn inflated with proper alpha blending, NEAREST filtering,
 *  diffuse lighting, soft ground shadow.
 * ═══════════════════════════════════════════════════════════════════════════
 */
public class SkinGLRenderer implements android.opengl.GLSurfaceView.Renderer {
    public float mAngleX = 0f, mAngleY = 0f, mZoomFactor = 1.0f, mLastX, mLastY;
    /** Whole-scene lift in world units (character + ground shadow). 0 = default. */
    public float mVerticalShift = 0f;
    /** Whole-scene horizontal nudge in world units. Positive = move the figure
     *  right, negative = left. Used by Home to optically centre the 3/4-view
     *  figure (whose head/body mass leans toward the raised hand) over the
     *  LAUNCH button axis. 0 = default (Skin Management is unaffected). */
    public float mHorizontalShift = 0f;
    public boolean mAutoRotate = false, mIsSlim = false;
    public float mRotateSpeed = 0.34f;                 // deg per frame
    public float mPoseLeftArmZ = 0f, mPoseRightArmZ = 0f, mPoseHeadYaw = 0f;
    /** Advanced rig (Home): forward arm swing, head pitch, torso lean, leg swing, float. */
    public float mPoseLeftArmX = 0f, mPoseRightArmX = 0f;
    public float mPoseHeadPitch = 0f;
    public float mPoseTorsoZ = 0f, mPoseTorsoX = 0f;
    public float mPoseLeftLegX = 0f, mPoseRightLegX = 0f;
    public float mPoseBobY = 0f;

    private int mProgram, mPositionHandle, mTextureCoordHandle, mNormalHandle;
    private int mMVPMatrixHandle, mModelMatrixHandle, mMVMatrixHandle, mTextureUniformHandle;
    private int mAlphaCutHandle = -1;
    private int mColorProgram, mColorPositionHandle, mColorMvpHandle, mColorUniformHandle;

    private final float[] mMVPMatrix = new float[16];
    private final float[] mProjectionMatrix = new float[16];
    private final float[] mViewMatrix = new float[16];
    private final float[] mModelMatrix = new float[16];
    private final float[] mPartModel = new float[16];
    private final float[] mMvScratch = new float[16];
    private final float[] mMvpScratch = new float[16];
    private final float[] mShadowModel = new float[16];

    private Cuboid mHead, mHeadLayer, mTorso, mTorsoLayer;
    private Cuboid mRightArm, mRightArmLayer, mLeftArm, mLeftArmLayer;
    private Cuboid mRightLeg, mRightLegLayer, mLeftLeg, mLeftLegLayer, mCape;
    private ShadowDisc mShadowDisc;

    private Bitmap mPendingSkinBitmap, mPendingCapeBitmap;

    // ── Minecraft-style name tag (Home) ─────────────────────────────────────
    private Bitmap mPendingNametag;
    private int mNametagTextureId = 0;
    private boolean mNametagNeedsUpdate = false;
    private FloatBuffer mNametagPos, mNametagUv, mNametagNorm;
    private float mNametagW = 8f, mNametagH = 1.9f;
    /** Set when a GL call ever fails on this device — draw nothing instead of
     *  crashing the GL thread (a dead preview beats a dead app). */
    private boolean mBroken = false;
    private boolean mFirstFrameDone = false;

    // ── Native-crash breadcrumbs ────────────────────────────────────────────
    // A SIGSEGV inside the vendor GL driver kills the process without any Java
    // exception, so the uncaught-exception reporter never runs. These phase
    // markers are persisted after every GL transition; the next launch checks
    // the last marker ("ok" = healthy) and, if it died mid-flight, writes a
    // diagnosis into latestcrash.txt.
    public static volatile String sGlPhase = "idle";
    public static volatile java.io.File sBreadcrumbFile;
    private static void mark(String phase) {
        sGlPhase = phase;
        java.io.File f = sBreadcrumbFile;
        if (f == null) return;
        try {
            java.io.PrintWriter w = new java.io.PrintWriter(f);
            w.println(phase);
            w.close();
        } catch (Throwable ignored) {}
    }
    private int mSkinTextureId = 0, mCapeTextureId = 0;
    private boolean mSkinTextureNeedsUpdate = false, mCapeTextureNeedsUpdate = false;

    private final Context mContext;
    public SkinGLRenderer(Context context) { mContext = context.getApplicationContext(); }

    /**
     * Minecraft-style name tag rendered above the head: translucent dark
     * plate, white pixel-font text (the launcher's bundled minecraftia),
     * always facing the camera. {@code null} removes it. Thread-safe: the
     * bitmap becomes a GL texture lazily on the render thread.
     */
    public synchronized void setNametag(String text) {
        if (text == null || text.isEmpty()) {
            mPendingNametag = null;
            mNametagNeedsUpdate = true;
            return;
        }
        try {
            android.graphics.Typeface tf = androidx.core.content.res.ResourcesCompat
                    .getFont(mContext, net.kdt.pojavlaunch.R.font.minecraftia);
            android.graphics.Paint pa = new android.graphics.Paint(
                    android.graphics.Paint.ANTI_ALIAS_FLAG);
            if (tf != null) pa.setTypeface(tf);
            pa.setTextSize(12f * 6f);                       // crisp, rendered large
            pa.setColor(0xFFFFFFFF);
            pa.setTextAlign(android.graphics.Paint.Align.LEFT);
            android.graphics.Paint.FontMetrics fm = pa.getFontMetrics();
            float textW = pa.measureText(text);
            float textH = fm.descent - fm.ascent;
            int padX = (int) (textH * 0.55f), padY = (int) (textH * 0.28f);
            int w = (int) (textW + padX * 2f), h = (int) (textH + padY * 2f);
            Bitmap bmp = Bitmap.createBitmap(Math.max(4, w), Math.max(4, h),
                    Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(bmp);
            // The vanilla name-plate: ~25% black, text pure white.
            android.graphics.Paint bg = new android.graphics.Paint();
            bg.setColor(0x5A000000);
            cv.drawRect(0, 0, bmp.getWidth(), bmp.getHeight(), bg);
            cv.drawText(text, padX, padY - fm.ascent, pa);
            mPendingNametag = bmp;
            mNametagNeedsUpdate = true;
            // world size: height fixed, width follows the text aspect
            mNametagH = 2.1f;
            mNametagW = Math.min(14f, mNametagH * bmp.getWidth() / (float) bmp.getHeight());
            // quad geometry (two triangles), normals aimed at the light so the
            // tag renders full-bright under the shared shader
            float hw = mNametagW / 2f, hh = mNametagH / 2f;
            float[] pos = { -hw, hh, 0f,  -hw, -hh, 0f,  hw, hh, 0f,
                             hw, hh, 0f,  -hw, -hh, 0f,  hw, -hh, 0f };
            float[] uv  = { 0f, 0f, 0f, 1f, 1f, 0f,  1f, 0f, 0f, 1f, 1f, 1f };
            float lvx = -0.38f, lvy = 0.82f, lvz = 0.42f;
            float ll = (float) Math.sqrt(lvx*lvx + lvy*lvy + lvz*lvz);
            float[] nrm = new float[18];
            for (int i = 0; i < 6; i++) { nrm[i*3] = lvx/ll; nrm[i*3+1] = lvy/ll; nrm[i*3+2] = lvz/ll; }
            mNametagPos = floatBuf(pos); mNametagUv = floatBuf(uv); mNametagNorm = floatBuf(nrm);
        } catch (Throwable t) {
            android.util.Log.w("SkinGLRenderer", "nametag build failed", t);
        }
    }

    private static FloatBuffer floatBuf(float[] arr) {
        java.nio.ByteBuffer bb = ByteBuffer.allocateDirect(arr.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(arr).position(0);
        return fb;
    }

    public synchronized void setTexture(Bitmap skin, Bitmap cape) {
        boolean slim = skin != null && detectSlim(skin);
        if (slim != mIsSlim) {
            mIsSlim = slim;
            clearCuboids();
        }
        mPendingSkinBitmap = skin;
        mPendingCapeBitmap = cape;
        mSkinTextureNeedsUpdate = true;
        mCapeTextureNeedsUpdate = true;
    }

    public void onPause() {
        // GL context is being torn down by GLSurfaceView; re-upload next frame.
        mSkinTextureId = 0;
        mCapeTextureId = 0;
        mNametagTextureId = 0;
        mSkinTextureNeedsUpdate = true;
        mCapeTextureNeedsUpdate = true;
        mNametagNeedsUpdate = true;
    }

    private static boolean detectSlim(@NonNull Bitmap skin) {
        return SkinAnalyzer.detectSkinModel(skin.getHeight(), (x, y) -> {
            if (x < 0 || y < 0 || x >= skin.getWidth() || y >= skin.getHeight()) return 0;
            return Color.alpha(skin.getPixel(x, y));
        }) == SkinModelType.ALEX;
    }

    @Override
    public void onSurfaceCreated(javax.microedition.khronos.opengles.GL10 gl,
                                 javax.microedition.khronos.egl.EGLConfig config) {
        mark("surface_created");
        try {
        onSurfaceCreatedImpl(config);
        mark("programs_ready");
        } catch (Throwable t) {
        mark("java_fail_surface");
        mBroken = true;
        android.util.Log.e("SkinGLRenderer", "surface init failed — renderer disabled", t);
        }
    }

    private void onSurfaceCreatedImpl(javax.microedition.khronos.egl.EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        int vs = loadShader(GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uMVPMatrix;\n" +
                "uniform mat4 uModelMatrix;\n" +
                "uniform mat4 uMVMatrix;\n" +
                "attribute vec4 aPosition;\n" +
                "attribute vec2 aTextureCoord;\n" +
                "attribute vec3 aNormal;\n" +
                "varying vec2 vTextureCoord;\n" +
                "varying vec3 vNormal;\n" +
                "varying vec3 vViewNormal;\n" +
                "varying vec3 vViewPos;\n" +
                "void main() {\n" +
                "  gl_Position = uMVPMatrix * aPosition;\n" +
                "  vTextureCoord = aTextureCoord;\n" +
                "  vNormal = normalize((uModelMatrix * vec4(aNormal, 0.0)).xyz);\n" +
                "  vViewNormal = normalize((uMVMatrix * vec4(aNormal, 0.0)).xyz);\n" +
                "  vViewPos = (uMVMatrix * aPosition).xyz;\n" +
                "}\n");
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float;\n" +
                "varying vec2 vTextureCoord;\n" +
                "varying vec3 vNormal;\n" +
                "varying vec3 vViewNormal;\n" +
                "varying vec3 vViewPos;\n" +
                "uniform sampler2D sTexture;\n" +
                "uniform float uAlphaCut;\n" +
                "void main() {\n" +
                "  vec4 color = texture2D(sTexture, vTextureCoord);\n" +
                "  if (color.a < uAlphaCut) discard;\n" +
                // studio key light (unchanged direction, wider falloff)
                "  vec3 lightDir = normalize(vec3(-0.38, 0.82, 0.42));\n" +
                "  float diffuse = max(dot(normalize(vNormal), lightDir), 0.0);\n" +
                "  float shade = 0.55 + diffuse * 0.48;\n" +
                // fresnel rim light: soft edge highlight = the premium look
                "  vec3 vn = normalize(vViewNormal);\n" +
                "  vec3 vd = normalize(-vViewPos);\n" +
                "  float rim = pow(1.0 - max(dot(vn, vd), 0.0), 2.4);\n" +
                "  shade += rim * 0.24;\n" +
                "  gl_FragColor = vec4(color.rgb * shade, color.a);\n" +
                "}\n");
        mProgram = linkProgram(vs, fs);
        mPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition");
        mTextureCoordHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord");
        mNormalHandle = GLES20.glGetAttribLocation(mProgram, "aNormal");
        mMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix");
        mModelMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uModelMatrix");
        mMVMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVMatrix");
        mTextureUniformHandle = GLES20.glGetUniformLocation(mProgram, "sTexture");
        mAlphaCutHandle = GLES20.glGetUniformLocation(mProgram, "uAlphaCut");

        int cvs = loadShader(GLES20.GL_VERTEX_SHADER,
                "uniform mat4 uMVPMatrix; attribute vec4 aPosition;\n" +
                "void main() { gl_Position = uMVPMatrix * aPosition; }\n");
        int cfs = loadShader(GLES20.GL_FRAGMENT_SHADER,
                "precision mediump float; uniform vec4 uColor;\n" +
                "void main() { gl_FragColor = uColor; }\n");
        mColorProgram = linkProgram(cvs, cfs);
        mColorPositionHandle = GLES20.glGetAttribLocation(mColorProgram, "aPosition");
        mColorMvpHandle = GLES20.glGetUniformLocation(mColorProgram, "uMVPMatrix");
        mColorUniformHandle = GLES20.glGetUniformLocation(mColorProgram, "uColor");
    }

    @Override
    public void onSurfaceChanged(javax.microedition.khronos.opengles.GL10 gl, int w, int h) {
        if (mBroken) return;
        try {
        Matrix.orthoM(mProjectionMatrix, 0,
                -18f * (float) w / h, 18f * (float) w / h,
                -18f, 18f, 0.1f, 200f);
        } catch (Throwable t) {
        mBroken = true;
        android.util.Log.e("SkinGLRenderer", "resize failed — renderer disabled", t);
        }
    }

    @Override
    public void onDrawFrame(javax.microedition.khronos.opengles.GL10 gl) {
        if (mBroken) return;
        try {
        onDrawFrameImpl(gl);
        if (!mFirstFrameDone) { mFirstFrameDone = true; mark("ok"); }
        } catch (Throwable t) {
        mark("java_fail_draw");
        mBroken = true;
        android.util.Log.e("SkinGLRenderer", "draw failed — renderer disabled", t);
        }
    }

    private void onDrawFrameImpl(javax.microedition.khronos.opengles.GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        synchronized (this) {
            if (mSkinTextureNeedsUpdate) {
                if (mSkinTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mSkinTextureId}, 0);
                mSkinTextureId = loadGLTexture(mPendingSkinBitmap);
                mSkinTextureNeedsUpdate = false;
            }
            if (mCapeTextureNeedsUpdate) {
                if (mCapeTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mCapeTextureId}, 0);
                mCapeTextureId = loadGLTexture(mPendingCapeBitmap);
                mCapeTextureNeedsUpdate = false;
            }
            if (mNametagNeedsUpdate) {
                if (mNametagTextureId != 0) GLES20.glDeleteTextures(1, new int[]{mNametagTextureId}, 0);
                mNametagTextureId = mPendingNametag != null ? loadGLTexture(mPendingNametag) : 0;
                mNametagNeedsUpdate = false;
            }
        }
        if (mSkinTextureId == 0) return;

        if (mAutoRotate) mAngleX = (mAngleX + mRotateSpeed) % 360f;
        rebuildCuboidsIfNeeded();

        Matrix.setLookAtM(mViewMatrix, 0,
                0f, 1.2f, 34f,
                0f, -2.0f, 0f,
                0f, 1f, 0f);
        if (mVerticalShift != 0f || mHorizontalShift != 0f)
            Matrix.translateM(mViewMatrix, 0, mHorizontalShift, mVerticalShift, 0f);
        Matrix.setIdentityM(mModelMatrix, 0);
        Matrix.rotateM(mModelMatrix, 0, mAngleY, 1f, 0f, 0f);
        Matrix.rotateM(mModelMatrix, 0, mAngleX, 0f, 1f, 0f);
        Matrix.scaleM(mModelMatrix, 0, mZoomFactor, mZoomFactor, mZoomFactor);
        if (mPoseBobY != 0f) Matrix.translateM(mModelMatrix, 0, 0f, mPoseBobY, 0f);

        // Optional pose (Home idle/greet). Zero = the plain management look.
        if (mLeftArm != null) {
            mLeftArm.rotZ = mPoseLeftArmZ;   mLeftArm.rotX = mPoseLeftArmX;
            mRightArm.rotZ = mPoseRightArmZ; mRightArm.rotX = mPoseRightArmX;
            mHead.rotY = mPoseHeadYaw;       mHead.rotX = mPoseHeadPitch;
            mTorso.rotZ = mPoseTorsoZ;       mTorso.rotX = mPoseTorsoX;
            mLeftLeg.rotX = mPoseLeftLegX;   mRightLeg.rotX = mPoseRightLegX;
            if (mLeftArmLayer != null)  { mLeftArmLayer.rotZ = mPoseLeftArmZ;   mLeftArmLayer.rotX = mPoseLeftArmX; }
            if (mRightArmLayer != null) { mRightArmLayer.rotZ = mPoseRightArmZ; mRightArmLayer.rotX = mPoseRightArmX; }
            if (mHeadLayer != null)     { mHeadLayer.rotY = mPoseHeadYaw;       mHeadLayer.rotX = mPoseHeadPitch; }
            if (mTorsoLayer != null)    { mTorsoLayer.rotZ = mPoseTorsoZ;       mTorsoLayer.rotX = mPoseTorsoX; }
            if (mLeftLegLayer != null)  { mLeftLegLayer.rotX = mPoseLeftLegX;   mRightLegLayer.rotX = mPoseRightLegX; }
        }

        drawShadow();

        GLES20.glUseProgram(mProgram);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        if (mAlphaCutHandle >= 0) GLES20.glUniform1f(mAlphaCutHandle, 0.08f);
        drawPart(mHead, mModelMatrix, mSkinTextureId);
        drawPart(mTorso, mModelMatrix, mSkinTextureId);
        drawPart(mRightArm, mModelMatrix, mSkinTextureId);
        drawPart(mLeftArm, mModelMatrix, mSkinTextureId);
        drawPart(mRightLeg, mModelMatrix, mSkinTextureId);
        drawPart(mLeftLeg, mModelMatrix, mSkinTextureId);

        if (mCapeTextureId != 0 && mCape != null) {
            System.arraycopy(mModelMatrix, 0, mPartModel, 0, 16);
            Matrix.translateM(mPartModel, 0, 0f, 8f, -2f);
            Matrix.rotateM(mPartModel, 0, 180f, 0f, 1f, 0f);
            Matrix.rotateM(mPartModel, 0, -10f, 1f, 0f, 0f);
            drawPart(mCape, mPartModel, mCapeTextureId);
        }

        // Overlay (hat / jacket / sleeves / pants) — Phase 11 "perfect cap":
        //  • back faces are culled, so the far side of the hat box can no
        //    longer show through transparent hat pixels (that was the ghost
        //    grid / doubled pixels on the cap);
        //  • the alpha cut-off is 0.5 like the vanilla client → every hat
        //    pixel is either fully there or not there, no smeared edges;
        //  • no blending is needed with a hard cut, so there is no ordering
        //    artefact between the six overlay boxes either.
        if (mAlphaCutHandle >= 0) GLES20.glUniform1f(mAlphaCutHandle, 0.5f);
        GLES20.glEnable(GLES20.GL_CULL_FACE);
        GLES20.glCullFace(GLES20.GL_BACK);
        GLES20.glFrontFace(GLES20.GL_CCW);
        drawPart(mHeadLayer, mModelMatrix, mSkinTextureId);
        drawPart(mTorsoLayer, mModelMatrix, mSkinTextureId);
        drawPart(mRightArmLayer, mModelMatrix, mSkinTextureId);
        drawPart(mLeftArmLayer, mModelMatrix, mSkinTextureId);
        drawPart(mRightLegLayer, mModelMatrix, mSkinTextureId);
        drawPart(mLeftLegLayer, mModelMatrix, mSkinTextureId);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        if (mAlphaCutHandle >= 0) GLES20.glUniform1f(mAlphaCutHandle, 0.08f);

        drawNametag();
    }

    /** Name plate floating above the head — vanilla Minecraft style. */
    private void drawNametag() {
        if (mNametagTextureId == 0 || mNametagPos == null) return;
        Matrix.setIdentityM(mPartModel, 0);
        Matrix.translateM(mPartModel, 0, 0f, 19.4f, 4.6f);
        Matrix.multiplyMM(mMvScratch, 0, mViewMatrix, 0, mPartModel, 0);
        Matrix.multiplyMM(mMvpScratch, 0, mProjectionMatrix, 0, mMvScratch, 0);
        GLES20.glUseProgram(mProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mNametagPos);
        GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
        GLES20.glVertexAttribPointer(mTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, mNametagUv);
        GLES20.glEnableVertexAttribArray(mNormalHandle);
        GLES20.glVertexAttribPointer(mNormalHandle, 3, GLES20.GL_FLOAT, false, 0, mNametagNorm);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mNametagTextureId);
        GLES20.glUniform1i(mTextureUniformHandle, 0);
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mMvpScratch, 0);
        GLES20.glUniformMatrix4fv(mModelMatrixHandle, 1, false, mPartModel, 0);
        GLES20.glUniformMatrix4fv(mMVMatrixHandle, 1, false, mMvScratch, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void rebuildCuboidsIfNeeded() {
        if (mHead != null) return;
        int armWidth = mIsSlim ? 3 : 4;
        float armHalf = armWidth / 2f;
        float armCenter = 4f + armHalf;

        mHead = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 0, 0, 8, 8, 8, 64, 64, false, 0f);
        mHeadLayer = new Cuboid(0, 8, 0, -4, 4, 0, 8, -4, 4, 32, 0, 8, 8, 8, 64, 64, false, 0.5f);
        mTorso = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 16, 8, 12, 4, 64, 64, false, 0f);
        mTorsoLayer = new Cuboid(0, 8, 0, -4, 4, -12, 0, -2, 2, 16, 32, 8, 12, 4, 64, 64, false, 0.25f);
        mRightArm = new Cuboid(-armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 40, 16, armWidth, 12, 4, 64, 64, false, 0f);
        mRightArmLayer = new Cuboid(-armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 40, 32, armWidth, 12, 4, 64, 64, false, 0.25f);
        mLeftArm = new Cuboid(armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 32, 48, armWidth, 12, 4, 64, 64, false, 0f);
        mLeftArmLayer = new Cuboid(armCenter, 8, 0, -armHalf, armHalf, -12, 0, -2, 2, 48, 48, armWidth, 12, 4, 64, 64, false, 0.25f);
        mRightLeg = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 16, 4, 12, 4, 64, 64, false, 0f);
        mRightLegLayer = new Cuboid(-2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 32, 4, 12, 4, 64, 64, false, 0.25f);
        mLeftLeg = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 16, 48, 4, 12, 4, 64, 64, false, 0f);
        mLeftLegLayer = new Cuboid(2, -4, 0, -2, 2, -12, 0, -2, 2, 0, 48, 4, 12, 4, 64, 64, false, 0.25f);
        // mirror=false — the visible outer cape face must read like it does
        // in-game (lettered/artwork capes used to render flipped).
        mCape = new Cuboid(0, 0, 0, -5, 5, -16, 0, 0, 1, 0, 0, 10, 16, 1, 64, 32, false, 0f);
    }

    private void clearCuboids() {
        mHead = mHeadLayer = mTorso = mTorsoLayer = null;
        mRightArm = mRightArmLayer = mLeftArm = mLeftArmLayer = null;
        mRightLeg = mRightLegLayer = mLeftLeg = mLeftLegLayer = null;
        mCape = null;
    }

    private void drawPart(Cuboid c, float[] baseModel, int textureId) {
        if (c == null || textureId == 0) return;
        System.arraycopy(baseModel, 0, mPartModel, 0, 16);
        Matrix.translateM(mPartModel, 0, c.pX, c.pY, c.pZ);
        // Optional per-part pose — rotates around the part's own pivot.
        if (c.rotZ != 0f) Matrix.rotateM(mPartModel, 0, c.rotZ, 0f, 0f, 1f);
        if (c.rotX != 0f) Matrix.rotateM(mPartModel, 0, c.rotX, 1f, 0f, 0f);
        if (c.rotY != 0f) Matrix.rotateM(mPartModel, 0, c.rotY, 0f, 1f, 0f);
        Matrix.multiplyMM(mMvScratch, 0, mViewMatrix, 0, mPartModel, 0);
        Matrix.multiplyMM(mMvpScratch, 0, mProjectionMatrix, 0, mMvScratch, 0);

        GLES20.glUseProgram(mProgram);
        GLES20.glEnableVertexAttribArray(mPositionHandle);
        GLES20.glVertexAttribPointer(mPositionHandle, 3, GLES20.GL_FLOAT, false, 0, c.vertexBuffer);
        GLES20.glEnableVertexAttribArray(mTextureCoordHandle);
        GLES20.glVertexAttribPointer(mTextureCoordHandle, 2, GLES20.GL_FLOAT, false, 0, c.uvBuffer);
        GLES20.glEnableVertexAttribArray(mNormalHandle);
        GLES20.glVertexAttribPointer(mNormalHandle, 3, GLES20.GL_FLOAT, false, 0, c.normalBuffer);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(mTextureUniformHandle, 0);
        GLES20.glUniformMatrix4fv(mMVPMatrixHandle, 1, false, mMvpScratch, 0);
        GLES20.glUniformMatrix4fv(mModelMatrixHandle, 1, false, mPartModel, 0);
        GLES20.glUniformMatrix4fv(mMVMatrixHandle, 1, false, mMvScratch, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 36);
    }

    private void drawShadow() {
        if (mShadowDisc == null) mShadowDisc = new ShadowDisc();
        Matrix.setIdentityM(mShadowModel, 0);
        Matrix.translateM(mShadowModel, 0, 0f, -16.18f, 0f);
        float shadowScale = 8.2f * mZoomFactor;
        Matrix.scaleM(mShadowModel, 0, shadowScale, 1f, shadowScale * 0.64f);
        Matrix.multiplyMM(mMvScratch, 0, mViewMatrix, 0, mShadowModel, 0);
        Matrix.multiplyMM(mMvpScratch, 0, mProjectionMatrix, 0, mMvScratch, 0);

        GLES20.glUseProgram(mColorProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDepthMask(false);
        GLES20.glEnableVertexAttribArray(mColorPositionHandle);
        GLES20.glVertexAttribPointer(mColorPositionHandle, 3, GLES20.GL_FLOAT, false, 0, mShadowDisc.vertexBuffer);
        GLES20.glUniformMatrix4fv(mColorMvpHandle, 1, false, mMvpScratch, 0);
        GLES20.glUniform4f(mColorUniformHandle, 0f, 0f, 0f, 0.34f);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, mShadowDisc.vertexCount);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private static int linkProgram(int vertexShader, int fragmentShader) {
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);
        return program;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        return shader;
    }

    private int loadGLTexture(Bitmap bitmap) {
        if (bitmap == null) return 0;
        // Legacy 64x32 (2:1) Steve-era skins lack the whole bottom half of the
        // modern grid — expand to the square grid first, mirroring right limbs
        // into the left limb boxes exactly like the 1.8+ client does.
        if (bitmap.getHeight() * 2 == bitmap.getWidth()) {
            Bitmap expanded = expandLegacySkin(bitmap);
            if (expanded != null) bitmap = expanded;
        }
        bitmap = clearSolidOverlayBoxes(bitmap);
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        return textures[0];
    }

    /**
     * Skins exported by old editors fill unused overlay boxes with opaque
     * black (or one flat colour). Drawn as a real layer that becomes a black
     * cube around the head / limbs. The game treats such a box as empty, so
     * we do the same: any overlay box that is one single opaque colour is
     * cleared to transparent before upload. Real hats always have variation.
     */
    private static Bitmap clearSolidOverlayBoxes(@NonNull Bitmap src) {
        int w = src.getWidth();
        if (w < 64 || w % 64 != 0 || src.getHeight() < w) return src;
        int s = w / 64;
        // {u, v, du, dv} of each overlay unwrap box on the 64-unit grid
        int[][] boxes = {
                {32, 0, 32, 16},   // hat
                {16, 32, 24, 16},  // jacket
                {40, 32, 16, 16},  // right sleeve
                {0, 32, 16, 16},   // right pants
                {48, 48, 16, 16},  // left sleeve
                {0, 48, 16, 16}};  // left pants
        Bitmap out = null;
        for (int[] b : boxes) {
            int bx = b[0] * s, by = b[1] * s, bw = b[2] * s, bh = b[3] * s;
            if (bx + bw > src.getWidth() || by + bh > src.getHeight()) continue;
            int[] px = new int[bw * bh];
            src.getPixels(px, 0, bw, bx, by, bw, bh);
            boolean solid = true;
            int first = px[0];
            if ((first >>> 24) != 0xFF) continue; // has transparency → a real layer
            for (int p : px) { if (p != first) { solid = false; break; } }
            if (!solid) continue;
            if (out == null) out = src.isMutable() ? src : src.copy(Bitmap.Config.ARGB_8888, true);
            if (out == null) return src;
            java.util.Arrays.fill(px, 0);
            out.setPixels(px, 0, bw, bx, by, bw, bh);
        }
        return out != null ? out : src;
    }

    /** 64x32 (Wx(W/2)) legacy grid → square grid with mirrored left limbs. */
    private static Bitmap expandLegacySkin(@NonNull Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        if (w != h * 2) return null;
        int s = w / 64;
        if (s < 1) return null;

        Bitmap out = Bitmap.createBitmap(w, w, Bitmap.Config.ARGB_8888);
        int[] buf = new int[w * h];
        src.getPixels(buf, 0, w, 0, 0, w, h);
        out.setPixels(buf, 0, w, 0, 0, w, h);

        // Right-arm box [40,16] → left-arm box [32,48];
        // right-leg box [0,16]  → left-leg box [16,48]  (64-unit grid).
        mirrorLimbBox(buf, out, w, 40, 16, 32, 48, s);
        mirrorLimbBox(buf, out, w,  0, 16, 16, 48, s);
        return out;
    }

    /** Horizontally flip one 16x16-unit limb unwrap box into its left-side slot. */
    private static void mirrorLimbBox(int[] src, Bitmap out, int w,
                                      int us, int vs, int du, int dv, int s) {
        int bw = 16 * s;
        int bh = 16 * s;
        int[] tmp = new int[bw * bh];
        int srcX0 = us * s, srcY0 = vs * s;
        for (int y = 0; y < bh; y++) {
            int srcRow = (srcY0 + y) * w;
            for (int x = 0; x < bw; x++) {
                tmp[y * bw + x] = src[srcRow + srcX0 + (bw - 1 - x)];
            }
        }
        out.setPixels(tmp, 0, bw, du * s, dv * s, bw, bh);
    }

    /** One textured box of the player model. */
    private static class Cuboid {
        public final FloatBuffer vertexBuffer;
        public final FloatBuffer uvBuffer;
        public final FloatBuffer normalBuffer;
        public final float pX, pY, pZ;
        public float rotZ = 0f, rotY = 0f, rotX = 0f;   // optional pose around the pivot

        public Cuboid(float px, float py, float pz,
                      float x1, float x2, float y1, float y2, float z1, float z2,
                      int us, int vs, int dx, int dy, int dz, int tw, int th,
                      boolean mirror, float expand) {
            pX = px; pY = py; pZ = pz;
            x1 -= expand; x2 += expand;
            y1 -= expand; y2 += expand;
            z1 -= expand; z2 += expand;

            float[] vertices = new float[108];
            float[] uvs = new float[72];
            float[] normals = new float[108];
            addFace(vertices, uvs, 0, 0,
                    x1, y2, z2, x1, y1, z2, x2, y1, z2, x2, y2, z2,
                    us + dz, vs + dz, dx, dy, tw, th, mirror);
            addFace(vertices, uvs, 18, 12,
                    x2, y2, z1, x2, y1, z1, x1, y1, z1, x1, y2, z1,
                    us + dz + dx + dz, vs + dz, dx, dy, tw, th, mirror);
            addFace(vertices, uvs, 36, 24,
                    x1, y2, z1, x1, y1, z1, x1, y1, z2, x1, y2, z2,
                    us, vs + dz, dz, dy, tw, th, mirror);
            addFace(vertices, uvs, 54, 36,
                    x2, y2, z2, x2, y1, z2, x2, y1, z1, x2, y2, z1,
                    us + dz + dx, vs + dz, dz, dy, tw, th, mirror);
            addFace(vertices, uvs, 72, 48,
                    x1, y2, z1, x1, y2, z2, x2, y2, z2, x2, y2, z1,
                    us + dz, vs, dx, dz, tw, th, mirror);
            addFace(vertices, uvs, 90, 60,
                    x1, y1, z2, x1, y1, z1, x2, y1, z1, x2, y1, z2,
                    us + dz + dx, vs, dx, dz, tw, th, mirror);

            putNormal(normals, 0, 0f, 0f, 1f);
            putNormal(normals, 18, 0f, 0f, -1f);
            putNormal(normals, 36, -1f, 0f, 0f);
            putNormal(normals, 54, 1f, 0f, 0f);
            putNormal(normals, 72, 0f, 1f, 0f);
            putNormal(normals, 90, 0f, -1f, 0f);

            vertexBuffer = ByteBuffer.allocateDirect(108 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
            uvBuffer = ByteBuffer.allocateDirect(72 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(uvs);
            normalBuffer = ByteBuffer.allocateDirect(108 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(normals);
            vertexBuffer.position(0);
            uvBuffer.position(0);
            normalBuffer.position(0);
        }

        private static void putNormal(float[] normals, int offset, float x, float y, float z) {
            for (int i = 0; i < 6; i++) {
                normals[offset + i * 3] = x;
                normals[offset + i * 3 + 1] = y;
                normals[offset + i * 3 + 2] = z;
            }
        }

        private static void addFace(float[] vertices, float[] uvs, int vi, int ui,
                                    float xa, float ya, float za,
                                    float xb, float yb, float zb,
                                    float xc, float yc, float zc,
                                    float xd, float yd, float zd,
                                    int us, int vs, int dx, int dy, int tw, int th, boolean mirror) {
            vertices[vi] = xa; vertices[vi + 1] = ya; vertices[vi + 2] = za;
            vertices[vi + 3] = xb; vertices[vi + 4] = yb; vertices[vi + 5] = zb;
            vertices[vi + 6] = xc; vertices[vi + 7] = yc; vertices[vi + 8] = zc;
            vertices[vi + 9] = xa; vertices[vi + 10] = ya; vertices[vi + 11] = za;
            vertices[vi + 12] = xc; vertices[vi + 13] = yc; vertices[vi + 14] = zc;
            vertices[vi + 15] = xd; vertices[vi + 16] = yd; vertices[vi + 17] = zd;

            float u1 = (float) us / tw, v1 = (float) vs / th;
            float u2 = (float) (us + dx) / tw, v2 = (float) (vs + dy) / th;
            if (mirror) { float tmp = u1; u1 = u2; u2 = tmp; }
            uvs[ui] = u1; uvs[ui + 1] = v1;
            uvs[ui + 2] = u1; uvs[ui + 3] = v2;
            uvs[ui + 4] = u2; uvs[ui + 5] = v2;
            uvs[ui + 6] = u1; uvs[ui + 7] = v1;
            uvs[ui + 8] = u2; uvs[ui + 9] = v2;
            uvs[ui + 10] = u2; uvs[ui + 11] = v1;
        }
    }

    /** Ground-contact soft ellipse under the player model. */
    private static class ShadowDisc {
        final FloatBuffer vertexBuffer;
        final int vertexCount;

        ShadowDisc() {
            int segments = 32;
            float[] vertices = new float[(segments + 2) * 3];
            vertices[0] = 0f; vertices[1] = 0f; vertices[2] = 0f;
            for (int i = 0; i <= segments; i++) {
                double angle = Math.PI * 2.0 * i / segments;
                int idx = (i + 1) * 3;
                vertices[idx] = (float) Math.cos(angle);
                vertices[idx + 1] = 0f;
                vertices[idx + 2] = (float) Math.sin(angle);
            }
            vertexCount = segments + 2;
            vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                    .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
            vertexBuffer.position(0);
        }
    }
}
