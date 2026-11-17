package net.sourceforge.opencamera.studio;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** Draws one or more camera textures into the OnePlus-style layouts (full, split, pip, grid)
 *  and can grade them (film, portrait bokeh) or accumulate a long exposure. */
public class GlCompositor implements GLSurfaceView.Renderer {
    public enum Layout {
        SINGLE, SPLIT, PIP, GRID
    }

    public enum Effect {
        NONE, FILM, PORTRAIT
    }

    public static final class Pane {
        public final int index;
        public final float x, y, w, h;

        Pane(int index, float x, float y, float w, float h) {
            this.index = index;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        public boolean contains(float nx, float ny) {
            return nx >= x && nx <= x + w && ny >= y && ny <= y + h;
        }
    }

    private static final class Stream {
        int texId;
        SurfaceTexture texture;
        Surface surface;
        final float [] matrix = new float[16];
        int bufW = 1280;
        int bufH = 720;
        volatile boolean pending;
        volatile boolean alive;
    }

    public interface Ready {
        void onGlReady();
    }

    private final Ready ready;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Stream> streams = new ArrayList<>();
    private final List<Pane> panes = new ArrayList<>();
    private final float [] quad = new float[6 * 4];
    private FloatBuffer quadBuf;
    private int program;
    private int aPos, aUv, aPane;
    private int uEffect, uLook, uBlur, uAlpha, uBorder, uRadius;
    private int blitProgram;
    private int blitPos, blitUv, blitSampler;
    private volatile Layout layout = Layout.PIP;
    private volatile Effect effect = Effect.NONE;
    private volatile int filmLook;
    private volatile float portraitBlur = 0.012f;
    private volatile int primary;
    private int viewW = 1, viewH = 1;
    private volatile boolean glReady;
    private int oldPrimary = -1;
    private long switchTime;
    private volatile boolean accumulate;
    private int accFrames;
    private int fbo;
    private int fboTex;
    private int fboW, fboH;
    private Runnable readCallback;
    private Bitmap readResult;

    public GlCompositor(Ready ready) {
        this.ready = ready;
        Matrix.setIdentityM(new float[16], 0);
    }

    public boolean isReady() {
        return glReady;
    }

    /** The EGL context is about to die. The next open waits for {@link #onSurfaceCreated}. */
    public void markContextLost() {
        glReady = false;
    }

    public void setLayout(Layout layout) {
        if( layout != null )
            this.layout = layout;
    }

    public void setEffect(Effect effect) {
        if( effect != null )
            this.effect = effect;
    }

    public void setFilmLook(int look) {
        this.filmLook = look;
    }

    public void setPortraitBlur(float blur) {
        this.portraitBlur = blur;
    }

    public void setPrimary(int index) {
        if( index == primary )
            return;
        oldPrimary = primary;
        primary = index;
        switchTime = System.nanoTime();
    }

    public int getPrimary() {
        return primary;
    }

    public void beginAccumulation() {
        accumulate = true;
        accFrames = 0;
    }

    public void endAccumulation() {
        accumulate = false;
    }

    public boolean isAccumulating() {
        return accumulate;
    }

    /** Creates the external texture on the GL thread and blocks until the Surface is ready. */
    public Surface addStream(GLSurfaceView view, int width, int height) {
        final Surface [] out = {null};
        final CountDownLatch latch = new CountDownLatch(1);
        view.queueEvent(() -> {
            try {
                Stream s = new Stream();
                s.bufW = Math.max(2, width);
                s.bufH = Math.max(2, height);
                s.alive = true;
                int [] tex = new int[1];
                GLES20.glGenTextures(1, tex, 0);
                s.texId = tex[0];
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                s.texture = new SurfaceTexture(s.texId);
                s.texture.setDefaultBufferSize(s.bufW, s.bufH);
                s.texture.setOnFrameAvailableListener(surfaceTexture -> {
                    s.pending = true;
                    view.requestRender();
                }, main);
                Matrix.setIdentityM(s.matrix, 0);
                s.surface = new Surface(s.texture);
                synchronized( streams ) {
                    streams.add(s);
                }
                out[0] = s.surface;
            }
            catch(Throwable t) {
                StudioLog.e("GL stream failed", t);
            }
            finally {
                latch.countDown();
            }
        });
        try {
            if( !latch.await(2, TimeUnit.SECONDS) )
                StudioLog.e("GL stream timed out");
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return out[0];
    }

    public void releaseStreams(GLSurfaceView view) {
        if( view == null || !glReady )
            return;
        final CountDownLatch latch = new CountDownLatch(1);
        view.queueEvent(() -> {
            releaseStreamsOnGl();
            latch.countDown();
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public List<Pane> copyPanes() {
        synchronized( panes ) {
            return new ArrayList<>(panes);
        }
    }

    /** Reads the current picture (the long-exposure buffer when one is running) and delivers it on {@code handler}. */
    public void capture(GLSurfaceView view, Handler handler, BitmapConsumer consumer) {
        view.queueEvent(() -> readCallback = () -> {
            Bitmap bmp = readResult;
            readResult = null;
            if( handler != null )
                handler.post(() -> consumer.onBitmap(bmp));
            else
                consumer.onBitmap(bmp);
        });
        view.requestRender();
    }

    public interface BitmapConsumer {
        void onBitmap(Bitmap bitmap);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        program = buildProgram(VERTEX, FRAGMENT);
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aUv = GLES20.glGetAttribLocation(program, "aUv");
        aPane = GLES20.glGetAttribLocation(program, "aPane");
        uEffect = GLES20.glGetUniformLocation(program, "uEffect");
        uLook = GLES20.glGetUniformLocation(program, "uLook");
        uBlur = GLES20.glGetUniformLocation(program, "uBlur");
        uAlpha = GLES20.glGetUniformLocation(program, "uAlpha");
        uBorder = GLES20.glGetUniformLocation(program, "uBorder");
        uRadius = GLES20.glGetUniformLocation(program, "uRadius");
        blitProgram = buildProgram(BLIT_VERTEX, BLIT_FRAGMENT);
        blitPos = GLES20.glGetAttribLocation(blitProgram, "aPos");
        blitUv = GLES20.glGetAttribLocation(blitProgram, "aUv");
        blitSampler = GLES20.glGetUniformLocation(blitProgram, "sTex");
        quadBuf = ByteBuffer.allocateDirect(quad.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        glReady = true;
        StudioLog.i("GL ready");
        if( ready != null )
            main.post(ready::onGlReady);
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        viewW = Math.max(1, width);
        viewH = Math.max(1, height);
        GLES20.glViewport(0, 0, viewW, viewH);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        synchronized( streams ) {
        for(Stream s : streams) {
            if( s.pending && s.texture != null ) {
                try {
                    s.texture.updateTexImage();
                    s.texture.getTransformMatrix(s.matrix);
                    s.pending = false;
                }
                catch(Throwable ignored) {
                }
            }
        }
        layoutPanes();
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        if( accumulate && !streams.isEmpty() ) {
            ensureFbo();
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, fboW, fboH);
            if( accFrames == 0 )
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            float alpha = 1.0f / (accFrames + 1);
            drawStream(streams.get(Math.max(0, Math.min(primary, streams.size() - 1))), 0.0f, 0.0f, 1.0f, 1.0f, alpha, false, 0.0f);
            accFrames++;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, viewW, viewH);
            blitTexture(fboTex);
        }
        else {
            float fade = 1.0f;
            if( oldPrimary >= 0 ) {
                float t = (System.nanoTime() - switchTime) / 1.0e9f;
                fade = Math.min(1.0f, t / 0.18f);
                if( fade >= 1.0f )
                    oldPrimary = -1;
            }
            List<Pane> local;
            synchronized( panes ) {
                local = new ArrayList<>(panes);
            }
            if( fade < 1.0f && oldPrimary >= 0 && oldPrimary < streams.size() ) {
                drawStream(streams.get(oldPrimary), 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, false, 0.0f);
            }
            for(Pane pane : local) {
                if( pane.index < 0 || pane.index >= streams.size() )
                    continue;
                float alpha = (fade < 1.0f && pane.index == primary && pane.w > 0.9f) ? fade : 1.0f;
                boolean border = streams.size() > 1 && pane.index == primary && pane.w < 0.95f;
                float radius = pane.w < 0.95f ? 0.06f : 0.0f;
                drawStream(streams.get(pane.index), pane.x, pane.y, pane.w, pane.h, alpha, border, radius);
            }
        }
        if( readCallback != null ) {
            readResult = readPixels();
            Runnable cb = readCallback;
            readCallback = null;
            cb.run();
        }
        }
    }

    private void drawStream(Stream s, float x, float y, float w, float h, float alpha, boolean border, float radius) {
        if( s == null || !s.alive || program == 0 )
            return;
        float [] crop = centerCrop(s, w, h);
        // Camera2 supplies sensor rotation and front mirroring in the texture matrix.
        // The Studio window is portrait-locked; phone orientation is capture metadata only.
        putQuad(x, y, w, h, Orient.textureCoordinates(s.matrix, crop[0], crop[1], crop[2], crop[3]));
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, s.texId);
        int effectId = 0;
        if( effect == Effect.FILM )
            effectId = 1;
        else if( effect == Effect.PORTRAIT )
            effectId = 2;
        // grades apply to the large pane only; the small previews stay clean
        if( w < 0.9f )
            effectId = 0;
        GLES20.glUniform1i(uEffect, effectId);
        GLES20.glUniform1i(uLook, filmLook);
        GLES20.glUniform1f(uBlur, portraitBlur);
        GLES20.glUniform1f(uAlpha, alpha);
        GLES20.glUniform1f(uBorder, border ? 1.0f : 0.0f);
        GLES20.glUniform2f(uRadius, radius, radius);
        if( alpha < 0.999f ) {
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        }
        drawQuad(aPos, aUv, aPane);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private float [] centerCrop(Stream s, float paneW, float paneH) {
        float paneAspect = (paneW * viewW) / Math.max(1.0f, paneH * viewH);
        return Orient.previewCrop(s.bufW, s.bufH, paneAspect, s.matrix);
    }

    private void layoutPanes() {
        int n;
        synchronized( streams ) {
            n = streams.size();
        }
        List<Pane> next = new ArrayList<>();
        if( n == 0 ) {
            publish(next);
            return;
        }
        int p = Math.max(0, Math.min(primary, n - 1));
        Layout use = layout;
        if( n == 1 )
            use = Layout.SINGLE;
        else if( use == Layout.GRID && n < 3 )
            use = Layout.SPLIT;
        if( use == Layout.SINGLE ) {
            next.add(new Pane(p, 0.0f, 0.0f, 1.0f, 1.0f));
        }
        else if( use == Layout.SPLIT ) {
            int other = p == 0 ? 1 : 0;
            if( other >= n )
                other = 0;
            next.add(new Pane(p, 0.0f, 0.0f, 1.0f, 0.5f));
            next.add(new Pane(other, 0.0f, 0.5f, 1.0f, 0.5f));
            float y = 0.62f;
            for(int i = 0; i < n; i++) {
                if( i == p || i == other )
                    continue;
                next.add(new Pane(i, 0.70f, y, 0.26f, 0.16f));
                y += 0.18f;
            }
        }
        else if( use == Layout.GRID ) {
            if( n == 3 ) {
                next.add(new Pane(p, 0.0f, 0.0f, 0.5f, 1.0f));
                float y = 0.0f;
                for(int i = 0; i < n; i++) {
                    if( i == p )
                        continue;
                    next.add(new Pane(i, 0.5f, y, 0.5f, 0.5f));
                    y += 0.5f;
                }
            }
            else {
                int cols = 2;
                int rows = (n + 1) / 2;
                float cw = 1.0f / cols;
                float ch = 1.0f / rows;
                for(int i = 0; i < n; i++) {
                    int col = i % cols;
                    int row = i / cols;
                    next.add(new Pane(i, col * cw, row * ch, cw, ch));
                }
            }
        }
        else {
            next.add(new Pane(p, 0.0f, 0.0f, 1.0f, 1.0f));
            float margin = 0.03f;
            float pw = 0.30f;
            float ph = pw * (viewW / (float)viewH) * (16.0f / 9.0f);
            if( ph < 0.16f )
                ph = 0.16f;
            if( ph > 0.28f )
                ph = 0.28f;
            float y = 0.16f;
            for(int i = 0; i < n; i++) {
                if( i == p )
                    continue;
                next.add(new Pane(i, 1.0f - margin - pw, y, pw, ph));
                y += ph + 0.012f;
            }
        }
        publish(next);
    }

    private void publish(List<Pane> next) {
        synchronized( panes ) {
            panes.clear();
            panes.addAll(next);
        }
    }

    private void ensureFbo() {
        int w = 1080;
        int h = 1920;
        if( viewW > viewH ) {
            w = 1920;
            h = 1080;
        }
        if( fbo != 0 && w == fboW && h == fboH )
            return;
        releaseFbo();
        int [] ids = new int[1];
        GLES20.glGenFramebuffers(1, ids, 0);
        fbo = ids[0];
        GLES20.glGenTextures(1, ids, 0);
        fboTex = ids[0];
        fboW = w;
        fboH = h;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTex, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void blitTexture(int tex) {
        putQuad(0.0f, 0.0f, 1.0f, 1.0f, Orient.textureCoordinates(null, 0.0f, 0.0f, 1.0f, 1.0f));
        GLES20.glUseProgram(blitProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(blitSampler, 0);
        drawQuad(blitPos, blitUv, -1);
    }

    private void putQuad(float x, float y, float w, float h, float [] uv) {
        float x0 = x * 2.0f - 1.0f;
        float x1 = (x + w) * 2.0f - 1.0f;
        float y0 = 1.0f - (y + h) * 2.0f;
        float y1 = 1.0f - y * 2.0f;
        // triangle strip: TL TR BL BR in view space (y up)
        float [] p = {
                x0, y1, uv[0], uv[1], 0.0f, 0.0f,
                x1, y1, uv[2], uv[3], 1.0f, 0.0f,
                x0, y0, uv[4], uv[5], 0.0f, 1.0f,
                x1, y0, uv[6], uv[7], 1.0f, 1.0f
        };
        quadBuf.position(0);
        quadBuf.put(p);
        quadBuf.position(0);
    }

    private void drawQuad(int pos, int uv, int pane) {
        int stride = 6 * 4;
        quadBuf.position(0);
        GLES20.glEnableVertexAttribArray(pos);
        GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, stride, quadBuf);
        quadBuf.position(2);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, stride, quadBuf);
        if( pane >= 0 ) {
            quadBuf.position(4);
            GLES20.glEnableVertexAttribArray(pane);
            GLES20.glVertexAttribPointer(pane, 2, GLES20.GL_FLOAT, false, stride, quadBuf);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(pos);
        GLES20.glDisableVertexAttribArray(uv);
        if( pane >= 0 )
            GLES20.glDisableVertexAttribArray(pane);
    }

    private Bitmap readPixels() {
        int w = viewW;
        int h = viewH;
        int fboBound = 0;
        if( accumulate && fbo != 0 ) {
            w = fboW;
            h = fboH;
            fboBound = fbo;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        }
        if( w > 1920 ) {
            // read the full buffer; callers scale. Cap memory by reading a centered crop only when huge.
        }
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
        if( fboBound != 0 )
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        // GL origin is bottom-left
        int row = w * 4;
        byte [] src = new byte[row];
        byte [] dst = new byte[w * h * 4];
        for(int y = 0; y < h; y++) {
            buf.position((h - 1 - y) * row);
            buf.get(src, 0, row);
            System.arraycopy(src, 0, dst, y * row, row);
        }
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(dst));
        return bmp;
    }

    private void releaseStreamsOnGl() {
        List<Stream> dying;
        synchronized( streams ) {
            dying = new ArrayList<>(streams);
            streams.clear();
            primary = 0;
            oldPrimary = -1;
        }
        for(Stream s : dying) {
            s.alive = false;
            s.pending = false;
            if( s.surface != null )
                s.surface.release();
            if( s.texture != null )
                s.texture.release();
            if( s.texId != 0 ) {
                int [] tex = {s.texId};
                GLES20.glDeleteTextures(1, tex, 0);
            }
        }
        accumulate = false;
        accFrames = 0;
        releaseFbo();
        publish(new ArrayList<Pane>());
    }

    private void releaseFbo() {
        if( fbo != 0 ) {
            int [] ids = {fbo};
            GLES20.glDeleteFramebuffers(1, ids, 0);
            fbo = 0;
        }
        if( fboTex != 0 ) {
            int [] ids = {fboTex};
            GLES20.glDeleteTextures(1, ids, 0);
            fboTex = 0;
        }
    }

    private static int buildProgram(String vs, String fs) {
        int v = compile(GLES20.GL_VERTEX_SHADER, vs);
        int f = compile(GLES20.GL_FRAGMENT_SHADER, fs);
        if( v == 0 || f == 0 )
            return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int [] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if( ok[0] == 0 ) {
            StudioLog.e("GL link: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int [] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if( ok[0] == 0 ) {
            StudioLog.e("GL shader: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private static final String VERTEX =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "attribute vec2 aPane;\n" +
            "varying vec2 vUv;\n" +
            "varying vec2 vPane;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vUv = aUv;\n" +
            "  vPane = aPane;\n" +
            "}\n";

    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "varying vec2 vPane;\n" +
            "uniform samplerExternalOES sTex;\n" +
            "uniform int uEffect;\n" +
            "uniform int uLook;\n" +
            "uniform float uBlur;\n" +
            "uniform float uAlpha;\n" +
            "uniform float uBorder;\n" +
            "uniform vec2 uRadius;\n" +
            "void main() {\n" +
            "  vec2 p = vPane;\n" +
            "  vec2 r = uRadius;\n" +
            "  if (r.x > 0.001) {\n" +
            "    if (p.x < r.x && p.y < r.y && distance(p, r) > r.x) discard;\n" +
            "    if (p.x > 1.0 - r.x && p.y < r.y && distance(p, vec2(1.0 - r.x, r.y)) > r.x) discard;\n" +
            "    if (p.x < r.x && p.y > 1.0 - r.y && distance(p, vec2(r.x, 1.0 - r.y)) > r.x) discard;\n" +
            "    if (p.x > 1.0 - r.x && p.y > 1.0 - r.y && distance(p, vec2(1.0 - r.x, 1.0 - r.y)) > r.x) discard;\n" +
            "  }\n" +
            "  vec4 c = texture2D(sTex, vUv);\n" +
            "  if (uEffect == 1) {\n" +
            "    float l = dot(c.rgb, vec3(0.30, 0.59, 0.11));\n" +
            "    if (uLook == 2) {\n" +
            "      c.rgb = vec3(l);\n" +
            "    } else {\n" +
            "      c.rgb = (c.rgb - 0.5) * 1.12 + 0.5;\n" +
            "      c.rgb = mix(c.rgb, vec3(l), 0.15);\n" +
            "      if (uLook == 1) { c.b *= 1.08; c.r *= 0.94; }\n" +
            "      else { c.r *= 1.07; c.b *= 0.90; }\n" +
            "      c.rgb = c.rgb * 0.92 + 0.04;\n" +
            "    }\n" +
            "  } else if (uEffect == 2) {\n" +
            "    vec2 dvec = vPane - vec2(0.5, 0.42);\n" +
            "    float d = length(dvec);\n" +
            "    float mask = smoothstep(0.16, 0.46, d);\n" +
            "    vec4 blur = vec4(0.0);\n" +
            "    for (int i = 0; i < 8; i++) {\n" +
            "      float a = float(i) * 0.785398;\n" +
            "      vec2 o = vec2(cos(a), sin(a)) * uBlur * mask;\n" +
            "      blur += texture2D(sTex, vUv + o);\n" +
            "    }\n" +
            "    c = mix(c, blur / 8.0, mask);\n" +
            "  }\n" +
            "  float edge = min(min(p.x, 1.0 - p.x), min(p.y, 1.0 - p.y));\n" +
            "  if (uBorder > 0.5 && edge < 0.02) c.rgb = mix(c.rgb, vec3(0.92, 0.0, 0.16), 0.9);\n" +
            "  c.a *= uAlpha;\n" +
            "  gl_FragColor = c;\n" +
            "}\n";

    private static final String BLIT_VERTEX =
            "attribute vec2 aPos;\n" +
            "attribute vec2 aUv;\n" +
            "varying vec2 vUv;\n" +
            "void main() {\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "  vUv = aUv;\n" +
            "}\n";

    private static final String BLIT_FRAGMENT =
            "precision mediump float;\n" +
            "varying vec2 vUv;\n" +
            "uniform sampler2D sTex;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(sTex, vUv);\n" +
            "}\n";
}
