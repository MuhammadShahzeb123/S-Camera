package com.shahzeb.scamera.studio;

import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Camera2 input textures and one persistent recorder target, owned by a dedicated EGL thread.
 * The caller owns the preview/recorder Surfaces; this class owns each Input Surface.
 * Construct, create/release inputs, start/stop the encoder and close from a background thread.
 * Successful stopEncoder/close returns are barriers: no encoder draw can follow them.
 */
public final class OpticalVideoRenderer implements AutoCloseable {
    /** App-local callbacks also work on API 23, before java.util.function is available. */
    public interface BitmapCallback { void accept(Bitmap bitmap); }
    public interface ErrorListener { void accept(RuntimeException error); }
    private static final String TAG = "OpticalVideoRenderer";
    private static final long WAIT_SECONDS = 5;
    private static final long HANDOFF_NANOS = 180_000_000L;
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    public interface FrameListener {
        /** Called exactly once after this input's first acquired frame, on the EGL thread. */
        void onFirstFrame(Input input);
    }

    public static final class Input {
        public final Surface surface;
        public final int width;
        public final int height;
        private final OpticalVideoRenderer owner;
        private final SurfaceTexture texture;
        private final int textureId;
        private final float[] transform = new float[16];
        private FrameListener firstFrameListener;
        private boolean hasFrame;
        private boolean retired;
        private boolean destroyed;
        private float nativeZoom = 1.0f;
        private boolean blend = true;

        private Input(OpticalVideoRenderer owner, int width, int height, int textureId,
                      SurfaceTexture texture, FrameListener firstFrameListener) {
            this.owner = owner;
            this.width = width;
            this.height = height;
            this.textureId = textureId;
            this.texture = texture;
            this.surface = new Surface(texture);
            this.firstFrameListener = firstFrameListener;
        }
    }

    private interface Work<T> {
        T run();
    }

    private static final class RenderException extends IllegalStateException {
        RenderException(String message) {
            super(message);
        }

        RenderException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final Object submissionLock = new Object();
    private final HandlerThread thread;
    private final Handler handler;
    private final CountDownLatch closed = new CountDownLatch(1);
    private final Set<Input> inputs = new HashSet<>();
    private final FloatBuffer vertices = floatBuffer(new float[]{-1, 1, 1, 1, -1, -1, 1, -1});
    private final FloatBuffer coordinates = floatBuffer(new float[8]);
    private volatile boolean closing;
    private volatile RuntimeException failure;
    private volatile RuntimeException closeFailure;
    private volatile ErrorListener errorListener;
    private RuntimeException notifiedFailure;
    private ErrorListener notifiedListener;

    // The remaining state is accessed only on the EGL thread.
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLConfig config;
    private EGLSurface maintenanceSurface = EGL14.EGL_NO_SURFACE;
    private EGLSurface preview = EGL14.EGL_NO_SURFACE;
    private EGLSurface encoder = EGL14.EGL_NO_SURFACE;
    private int previewWidth;
    private int previewHeight;
    private int encoderWidth;
    private int encoderHeight;
    private int program;
    private int positionAttribute;
    private int uvAttribute;
    private int samplerUniform;
    private int opacityUniform;
    private int gradeUniform, balanceUniform, zebraUniform;
    private ColorGrade colorGrade = ColorGrade.NEUTRAL;
    private boolean zebras;
    private Input requestedInput;
    private Input displayedInput;
    private Input precedingInput;
    private final ZoomRamp zoomRamp = new ZoomRamp();
    private float desiredGlobalZoom = 1f, handoffZoom = 1f;
    private long handoffStarted;
    private OpticalVideoMath.FrameClock frameClock;
    private final Runnable encoderTick = () -> runAsync(this::drawEncoderTick);
    private final Runnable animationTick = () -> runAsync(this::drawPreview);

    public OpticalVideoRenderer(Surface previewSurface, int previewWidth, int previewHeight) {
        requireSurface(previewSurface);
        requireDimensions(previewWidth, previewHeight);
        this.previewWidth = previewWidth;
        this.previewHeight = previewHeight;
        thread = new HandlerThread("OpticalVideoEGL");
        thread.start();
        handler = new Handler(thread.getLooper());
        try {
            call("initialize EGL", false, () -> {
                initialize(previewSurface);
                return null;
            });
        }
        catch(RuntimeException e) {
            try {
                close();
            }
            catch(RuntimeException cleanupError) {
                e.addSuppressed(cleanupError);
            }
            throw e;
        }
    }

    /** The same grade is used by the display, still readback and encoder. */
    public void setColorGrade(ColorGrade grade) {
        if( grade == null ) throw new IllegalArgumentException("Color grade is required");
        post(() -> { colorGrade = grade; drawPreview(); });
    }

    /** Highlight monitoring is never burned into encoded frames or snapshots. */
    public void setZebras(boolean enabled) {
        post(() -> { zebras = enabled; drawPreview(); });
    }

    /** Asynchronous failures are delivered on the EGL thread; blocking calls also throw. */
    public void setErrorListener(ErrorListener listener) {
        errorListener = listener;
        handler.post(this::notifyFailure);
    }

    /** The supplied Surface can also be a Canvas producer for renderer instrumentation tests. */
    public Input createInput(int width, int height, FrameListener listener) {
        requireDimensions(width, height);
        return call("create camera input", false, () -> {
            makeCurrent(maintenanceSurface);
            int textureId = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            SurfaceTexture texture = null;
            Input input = null;
            try {
                texture = new SurfaceTexture(textureId);
                texture.setDefaultBufferSize(width, height);
                input = new Input(this, width, height, textureId, texture, listener);
                final Input created = input;
                texture.setOnFrameAvailableListener(ignored -> runAsync(() -> acquireFrame(created)), handler);
                inputs.add(input);
                return input;
            }
            catch(RuntimeException e) {
                if( input != null )
                    input.surface.release();
                if( texture != null )
                    texture.release();
                GLES20.glDeleteTextures(1, new int[]{textureId}, 0);
                throw new RenderException("Cannot create camera input", e);
            }
        });
    }

    /** Keep the preceding picture until this input actually supplies a frame. */
    public void activate(Input input, float digitalZoom) {
        activate(input, digitalZoom, 1f, true);
    }

    /** Native zoom keeps both sides of a lens handoff in the same field-of-view coordinates. */
    public void activate(Input input, float digitalZoom, float nativeZoom, boolean blend) {
        requireOwned(input);
        OpticalVideoMath.checkZoom(digitalZoom);
        ZoomRamp.check(nativeZoom);
        ZoomRamp.check(digitalZoom * nativeZoom);
        post(() -> {
            if( input.retired || input.destroyed )
                return;
            input.nativeZoom = nativeZoom;
            input.blend = blend;
            desiredGlobalZoom = digitalZoom * nativeZoom;
            requestedInput = input;
            if( displayedInput == null || !blend ) zoomRamp.reset(desiredGlobalZoom);
            else if( precedingInput == null ) zoomRamp.to(desiredGlobalZoom, System.nanoTime());
            drawPreview();
        });
    }

    /** Also animates the retained frame while the next physical camera is opening. */
    public void setGlobalZoom(float globalZoom) {
        ZoomRamp.check(globalZoom);
        post(() -> {
            desiredGlobalZoom = globalZoom;
            if( precedingInput == null ) zoomRamp.to(globalZoom, System.nanoTime());
            drawPreview();
        });
    }

    /**
     * Call after the camera device has closed. Its last acquired texture may be retained
     * for the recorder until a replacement first frame arrives; it is never acquired again.
     */
    public void releaseInput(Input input) {
        if( input == null )
            return;
        requireOwned(input);
        call("release camera input", true, () -> {
            if( input.retired || input.destroyed )
                return null;
            input.retired = true;
            input.firstFrameListener = null;
            input.texture.setOnFrameAvailableListener(null);
            input.surface.release();
            if( requestedInput == input )
                requestedInput = null;
            if( displayedInput != input && precedingInput != input ) {
                makeCurrent(maintenanceSurface);
                destroyInput(input);
            }
            return null;
        });
    }

    public void resizePreview(int width, int height) {
        requireDimensions(width, height);
        post(() -> {
            previewWidth = width;
            previewHeight = height;
            drawPreview();
        });
    }

    /** Call after MediaRecorder.start(); width/height remain fixed until stopEncoder(). */
    public void startEncoder(Surface recorderSurface, int width, int height, int fps) {
        requireSurface(recorderSurface);
        requireDimensions(width, height);
        if( fps <= 0 || fps > 1_000_000_000 )
            throw new IllegalArgumentException("Invalid video frame rate: " + fps);
        call("start video target", false, () -> {
            if( encoder != EGL14.EGL_NO_SURFACE )
                throw new IllegalStateException("Video target already started");
            encoder = createWindow(recorderSurface);
            encoderWidth = width;
            encoderHeight = height;
            frameClock = new OpticalVideoMath.FrameClock(fps, System.nanoTime());
            handler.post(encoderTick);
            return null;
        });
    }

    /** Call before MediaRecorder.stop(). Returns only after the last submitted encoder draw. */
    public void stopEncoder() {
        call("stop video target", true, () -> {
            stopEncoderOnThread();
            scheduleAnimation();
            return null;
        });
    }

    /** An upright preview capture, delivered on the EGL thread; null means no picture. */
    public void capture(BitmapCallback callback) {
        if( callback == null )
            throw new IllegalArgumentException("Missing capture callback");
        synchronized( submissionLock ) {
            ensureOpen(false);
            if( !handler.post(() -> {
                Bitmap result = null;
                try {
                    if( !closing && failure == null && displayedInput != null )
                        result = readPicture();
                }
                catch(RuntimeException e) {
                    recordFailure(e);
                }
                try {
                    callback.accept(result);
                }
                catch(RuntimeException e) {
                    Log.e(TAG, "Capture callback failed", e);
                }
            }) )
                throw new IllegalStateException("EGL thread has stopped");
        }
    }

    @Override
    public void close() {
        synchronized( submissionLock ) {
            if( !closing ) {
                closing = true;
                if( !isRenderThread() && !handler.post(this::closeOnThread) ) {
                    closeFailure = new IllegalStateException("EGL thread stopped before cleanup");
                    closed.countDown();
                }
            }
        }
        if( isRenderThread() ) {
            closeOnThread();
            if( closeFailure != null )
                throw closeFailure;
            return;
        }
        await(closed, "close EGL renderer");
        try {
            thread.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            if( thread.isAlive() )
                throw new IllegalStateException("Timed out waiting for EGL thread exit");
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for EGL thread exit", e);
        }
        if( closeFailure != null )
            throw closeFailure;
    }

    private void acquireFrame(Input input) {
        if( input.retired || input.destroyed )
            return;
        makeCurrent(maintenanceSurface);
        try {
            input.texture.updateTexImage();
            input.texture.getTransformMatrix(input.transform);
        }
        catch(RuntimeException e) {
            throw new RenderException("Cannot acquire camera frame", e);
        }
        boolean firstFrame = !input.hasFrame;
        input.hasFrame = true;
        if( firstFrame && input.firstFrameListener != null ) {
            FrameListener callback = input.firstFrameListener;
            input.firstFrameListener = null;
            callback.onFirstFrame(input);
        }
        // Recording ticks also refresh preview, keeping it alive across camera handoffs.
        if( encoder == EGL14.EGL_NO_SURFACE && (displayedInput == input || requestedInput == input) && !closing )
            drawPreview();
    }

    private void displayInput(Input input, long now) {
        if( displayedInput == input )
            return;
        Input preceding = displayedInput;
        float matchingZoom = Math.max(zoomRamp.value(now), Math.max(input.nativeZoom,
                preceding == null ? input.nativeZoom : preceding.nativeZoom));
        displayedInput = input;
        if( input.blend && preceding != null && preceding.hasFrame && !preceding.destroyed ) {
            precedingInput = preceding;
            handoffZoom = matchingZoom;
            handoffStarted = now;
            zoomRamp.reset(matchingZoom);
        }
        else {
            zoomRamp.reset(desiredGlobalZoom);
            releasePreceding(preceding);
        }
    }

    private void releasePreceding(Input input) {
        if( input != null && input.retired ) {
            // Called while drawing: retain the caller's preview, encoder or snapshot target.
            destroyInput(input);
        }
    }

    private void advanceTransition(long now) {
        if( precedingInput != null ) {
            if( now - handoffStarted < HANDOFF_NANOS ) return;
            releasePreceding(precedingInput);
            precedingInput = null;
            zoomRamp.to(desiredGlobalZoom, now);
        }
        Input next = requestedInput;
        if( next == null || next == displayedInput || !next.hasFrame || next.destroyed ) return;
        // A telephoto cannot show the wider field of view. Reach its native factor first.
        float visibleZoom = Math.max(zoomRamp.value(now), displayedInput == null ? 0f : displayedInput.nativeZoom);
        if( displayedInput == null || !next.blend || visibleZoom >= next.nativeZoom )
            displayInput(next, now);
    }

    private void scheduleAnimation() {
        handler.removeCallbacks(animationTick);
        if( !closing && failure == null && encoder == EGL14.EGL_NO_SURFACE
                && (precedingInput != null || zoomRamp.isRunning(System.nanoTime())) ) {
            handler.postDelayed(animationTick, 16);
        }
    }

    private void drawPreview() {
        makeCurrent(preview);
        drawPicture(previewWidth, previewHeight, true);
        if( !EGL14.eglSwapBuffers(display, preview) )
            throw eglFailure("eglSwapBuffers(preview)");
        scheduleAnimation();
    }

    private void drawEncoderTick() {
        if( encoder == EGL14.EGL_NO_SURFACE || frameClock == null )
            return;
        long now = System.nanoTime();
        if( now >= frameClock.nextFrameTime() ) {
            long timestamp = frameClock.takeFrame(now);
            makeCurrent(encoder);
            drawPicture(encoderWidth, encoderHeight, false);
            if( !EGLExt.eglPresentationTimeANDROID(display, encoder, timestamp) )
                throw eglFailure("eglPresentationTimeANDROID");
            if( !EGL14.eglSwapBuffers(display, encoder) )
                throw eglFailure("eglSwapBuffers(encoder)");
            drawPreview();
        }
        if( !closing && failure == null && frameClock != null ) {
            long until = Math.max(0, frameClock.nextFrameTime() - System.nanoTime());
            // Round up to avoid an early millisecond Handler wake-up submitting two slots.
            handler.postDelayed(encoderTick, (until + 999_999L) / 1_000_000L);
        }
    }

    private void drawPicture(int width, int height, boolean monitoring) {
        long now = System.nanoTime();
        advanceTransition(now);
        GLES20.glViewport(0, 0, width, height);
        GLES20.glClearColor(0, 0, 0, 1);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        Input input = displayedInput;
        if( input == null || !input.hasFrame || input.destroyed )
            return;
        float globalZoom = precedingInput != null ? handoffZoom : Math.max(input.nativeZoom, zoomRamp.value(now));
        if( precedingInput != null ) {
            drawInput(precedingInput, width, height, globalZoom, 1f, monitoring);
            float t = Math.min(1f, (now - handoffStarted) / (float)HANDOFF_NANOS);
            float alpha = t * t * (3f - 2f * t);
            GLES20.glEnable(GLES20.GL_BLEND);
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
            drawInput(input, width, height, globalZoom, alpha, monitoring);
            GLES20.glDisable(GLES20.GL_BLEND);
        }
        else drawInput(input, width, height, globalZoom, 1f, monitoring);
    }

    private void drawInput(Input input, int width, int height, float globalZoom, float opacity, boolean monitoring) {
        float[] uv = OpticalVideoMath.textureCoordinates(input.width, input.height,
                width, height, input.transform, Math.max(1f, globalZoom / input.nativeZoom));
        coordinates.position(0);
        coordinates.put(uv).position(0);
        vertices.position(0);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, input.textureId);
        GLES20.glUniform1i(samplerUniform, 0);
        GLES20.glUniform1f(opacityUniform, opacity);
        GLES20.glUniform3f(gradeUniform, colorGrade.gain(), colorGrade.contrast, colorGrade.saturation);
        GLES20.glUniform2f(balanceUniform, colorGrade.warmth, colorGrade.tint);
        GLES20.glUniform1f(zebraUniform, monitoring && zebras ? 1f : 0f);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        GLES20.glEnableVertexAttribArray(uvAttribute);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 0, vertices);
        GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 0, coordinates);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionAttribute);
        GLES20.glDisableVertexAttribArray(uvAttribute);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        checkGl("draw camera texture");
    }

    private Bitmap readPicture() {
        // Bound snapshot allocation, especially for very tall high-density preview displays.
        float scale = Math.min(1.0f, 1920.0f / Math.max(previewWidth, previewHeight));
        int width = Math.max(1, Math.round(previewWidth * scale));
        int height = Math.max(1, Math.round(previewHeight * scale));
        makeCurrent(maintenanceSurface);
        int texture = createTexture(GLES20.GL_TEXTURE_2D);
        int[] framebuffer = new int[1];
        try {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height,
                    0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glGenFramebuffers(1, framebuffer, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer[0]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D, texture, 0);
            if( GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) != GLES20.GL_FRAMEBUFFER_COMPLETE )
                throw new RenderException("Incomplete capture framebuffer");
            drawPicture(width, height, false);
            ByteBuffer pixels = ByteBuffer.allocateDirect(width * height * 4);
            GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels);
            checkGl("capture camera texture");
            int[] argb = new int[width * height];
            pixels.position(0);
            for(int y = 0; y < height; y++) {
                int row = (height - 1 - y) * width;
                for(int x = 0; x < width; x++) {
                    int red = pixels.get() & 255;
                    int green = pixels.get() & 255;
                    int blue = pixels.get() & 255;
                    int alpha = pixels.get() & 255;
                    argb[row + x] = (alpha << 24) | (red << 16) | (green << 8) | blue;
                }
            }
            return Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888);
        }
        finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, framebuffer, 0);
            GLES20.glDeleteTextures(1, new int[]{texture}, 0);
        }
    }

    private void initialize(Surface previewSurface) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if( display == EGL14.EGL_NO_DISPLAY )
            throw eglFailure("eglGetDisplay");
        int[] version = new int[2];
        if( !EGL14.eglInitialize(display, version, 0, version, 1) )
            throw eglFailure("eglInitialize");
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
                EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if( !EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0 )
            throw eglFailure("eglChooseConfig(recordable)");
        config = configs[0];
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if( context == null || context == EGL14.EGL_NO_CONTEXT )
            throw eglFailure("eglCreateContext");
        maintenanceSurface = EGL14.eglCreatePbufferSurface(display, config,
                new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        if( maintenanceSurface == null || maintenanceSurface == EGL14.EGL_NO_SURFACE )
            throw eglFailure("eglCreatePbufferSurface");
        makeCurrent(maintenanceSurface);
        program = buildProgram();
        positionAttribute = GLES20.glGetAttribLocation(program, "aPosition");
        uvAttribute = GLES20.glGetAttribLocation(program, "aUv");
        samplerUniform = GLES20.glGetUniformLocation(program, "uTexture");
        opacityUniform = GLES20.glGetUniformLocation(program, "uOpacity");
        gradeUniform = GLES20.glGetUniformLocation(program, "uGrade");
        balanceUniform = GLES20.glGetUniformLocation(program, "uBalance");
        zebraUniform = GLES20.glGetUniformLocation(program, "uZebra");
        if( positionAttribute < 0 || uvAttribute < 0 || samplerUniform < 0 || opacityUniform < 0
                || gradeUniform < 0 || balanceUniform < 0 || zebraUniform < 0 )
            throw new RenderException("Missing camera shader attribute/uniform");
        preview = createWindow(previewSurface);
        makeCurrent(preview);
        if( !EGL14.eglSwapInterval(display, 0) )
            throw eglFailure("eglSwapInterval");
        drawPreview();
    }

    private EGLSurface createWindow(Surface surface) {
        EGLSurface result = EGL14.eglCreateWindowSurface(display, config, surface,
                new int[]{EGL14.EGL_NONE}, 0);
        if( result == null || result == EGL14.EGL_NO_SURFACE )
            throw eglFailure("eglCreateWindowSurface");
        return result;
    }

    private void makeCurrent(EGLSurface surface) {
        if( !EGL14.eglMakeCurrent(display, surface, surface, context) )
            throw eglFailure("eglMakeCurrent");
    }

    private void stopEncoderOnThread() {
        handler.removeCallbacks(encoderTick);
        frameClock = null;
        if( encoder == EGL14.EGL_NO_SURFACE )
            return;
        EGLSurface ending = encoder;
        encoder = EGL14.EGL_NO_SURFACE;
        encoderWidth = encoderHeight = 0;
        RuntimeException error = null;
        try {
            makeCurrent(ending);
            GLES20.glFinish();
            checkGl("finish encoder draws");
        }
        catch(RuntimeException e) {
            error = e;
        }
        try {
            makeCurrent(maintenanceSurface);
        }
        catch(RuntimeException e) {
            error = combine(error, e);
        }
        if( !EGL14.eglDestroySurface(display, ending) )
            error = combine(error, eglFailure("eglDestroySurface(encoder)"));
        if( error != null )
            throw error;
    }

    private void destroyInput(Input input) {
        if( input.destroyed )
            return;
        input.destroyed = true;
        input.firstFrameListener = null;
        input.texture.setOnFrameAvailableListener(null);
        input.surface.release();
        input.texture.release();
        GLES20.glDeleteTextures(1, new int[]{input.textureId}, 0);
        inputs.remove(input);
    }

    private void closeOnThread() {
        if( closed.getCount() == 0 )
            return;
        try {
            closeFailure = releaseResources();
        }
        catch(RuntimeException e) {
            closeFailure = combine(closeFailure, e);
        }
        finally {
            closed.countDown();
            thread.quitSafely();
        }
    }

    private RuntimeException releaseResources() {
        handler.removeCallbacks(animationTick);
        RuntimeException error = null;
        try {
            stopEncoderOnThread();
        }
        catch(RuntimeException e) {
            error = e;
        }
        try {
            if( context != null && context != EGL14.EGL_NO_CONTEXT
                    && maintenanceSurface != null && maintenanceSurface != EGL14.EGL_NO_SURFACE )
                makeCurrent(maintenanceSurface);
        }
        catch(RuntimeException e) {
            error = combine(error, e);
        }
        for(Input input : new ArrayList<>(inputs)) {
            try {
                destroyInput(input);
            }
            catch(RuntimeException e) {
                error = combine(error, e);
            }
        }
        displayedInput = requestedInput = precedingInput = null;
        if( program != 0 ) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }
        if( display != null && display != EGL14.EGL_NO_DISPLAY ) {
            if( !EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT) )
                error = combine(error, eglFailure("eglMakeCurrent(release)"));
            if( preview != null && preview != EGL14.EGL_NO_SURFACE
                    && !EGL14.eglDestroySurface(display, preview) )
                error = combine(error, eglFailure("eglDestroySurface(preview)"));
            if( maintenanceSurface != null && maintenanceSurface != EGL14.EGL_NO_SURFACE
                    && !EGL14.eglDestroySurface(display, maintenanceSurface) )
                error = combine(error, eglFailure("eglDestroySurface(maintenance)"));
            if( context != null && context != EGL14.EGL_NO_CONTEXT
                    && !EGL14.eglDestroyContext(display, context) )
                error = combine(error, eglFailure("eglDestroyContext"));
            if( !EGL14.eglTerminate(display) )
                error = combine(error, eglFailure("eglTerminate"));
            if( !EGL14.eglReleaseThread() )
                error = combine(error, eglFailure("eglReleaseThread"));
        }
        preview = maintenanceSurface = EGL14.EGL_NO_SURFACE;
        context = EGL14.EGL_NO_CONTEXT;
        display = EGL14.EGL_NO_DISPLAY;
        return error;
    }

    private <T> T call(String operation, boolean allowFailure, Work<T> work) {
        if( isRenderThread() ) {
            ensureOpen(allowFailure);
            return work.run();
        }
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<RuntimeException> error = new AtomicReference<>();
        Runnable task = () -> {
            try {
                if( cancelled.get() )
                    return;
                ensureOpen(allowFailure);
                result.set(work.run());
            }
            catch(RuntimeException e) {
                error.set(e);
                if( e instanceof RenderException )
                    recordFailure(e);
            }
            finally {
                completed.countDown();
            }
        };
        synchronized( submissionLock ) {
            ensureOpen(allowFailure);
            if( !handler.post(task) )
                throw new IllegalStateException("EGL thread has stopped");
        }
        try {
            await(completed, operation);
        }
        catch(RuntimeException e) {
            cancelled.set(true);
            handler.removeCallbacks(task);
            recordFailure(e);
            throw e;
        }
        if( error.get() != null )
            throw error.get();
        return result.get();
    }

    private void post(Runnable work) {
        if( isRenderThread() ) {
            ensureOpen(false);
            runAsync(work);
            return;
        }
        synchronized( submissionLock ) {
            ensureOpen(false);
            if( !handler.post(() -> runAsync(work)) )
                throw new IllegalStateException("EGL thread has stopped");
        }
    }

    private void runAsync(Runnable work) {
        if( closing || failure != null )
            return;
        try {
            work.run();
        }
        catch(RuntimeException e) {
            recordFailure(e);
        }
    }

    private void ensureOpen(boolean allowFailure) {
        if( closing )
            throw new IllegalStateException("EGL renderer is closed");
        if( failure != null && !allowFailure )
            throw failure;
    }

    private void recordFailure(RuntimeException error) {
        synchronized( submissionLock ) {
            if( failure == null ) {
                failure = error;
                Log.e(TAG, "Renderer failed", error);
            }
        }
        if( isRenderThread() ) {
            handler.removeCallbacks(encoderTick);
            notifyFailure();
        }
        else
            handler.post(this::notifyFailure);
    }

    private void notifyFailure() {
        ErrorListener listener = errorListener;
        RuntimeException error = failure;
        if( listener == null || error == null
                || (listener == notifiedListener && error == notifiedFailure) )
            return;
        notifiedFailure = error;
        notifiedListener = listener;
        try {
            listener.accept(error);
        }
        catch(RuntimeException e) {
            Log.e(TAG, "Renderer error callback failed", e);
        }
    }

    private boolean isRenderThread() {
        return Looper.myLooper() == handler.getLooper();
    }

    private void requireOwned(Input input) {
        if( input == null || input.owner != this )
            throw new IllegalArgumentException("Input belongs to another renderer");
    }

    private static void requireSurface(Surface surface) {
        if( surface == null || !surface.isValid() )
            throw new IllegalArgumentException("Missing or invalid output surface");
    }

    private static void requireDimensions(int width, int height) {
        if( width <= 0 || height <= 0 )
            throw new IllegalArgumentException("Invalid video dimensions: " + width + "x" + height);
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if( !latch.await(WAIT_SECONDS, TimeUnit.SECONDS) )
                throw new IllegalStateException("Timed out waiting to " + operation);
        }
        catch(InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting to " + operation, e);
        }
    }

    private static RuntimeException combine(RuntimeException first, RuntimeException next) {
        if( first == null )
            return next;
        if( first != next )
            first.addSuppressed(next);
        return first;
    }

    private static RenderException eglFailure(String operation) {
        return new RenderException(operation + " failed: EGL 0x"
                + Integer.toHexString(EGL14.eglGetError()));
    }

    private static void checkGl(String operation) {
        int error = GLES20.glGetError();
        if( error != GLES20.GL_NO_ERROR )
            throw new RenderException(operation + " failed: GL 0x" + Integer.toHexString(error));
    }

    private static int createTexture(int target) {
        int[] texture = new int[1];
        GLES20.glGenTextures(1, texture, 0);
        GLES20.glBindTexture(target, texture[0]);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        checkGl("create texture");
        return texture[0];
    }

    private static int buildProgram() {
        int vertex = compileShader(GLES20.GL_VERTEX_SHADER,
                "attribute vec4 aPosition;\nattribute vec2 aUv;\nvarying vec2 vUv;\n"
                        + "void main() { gl_Position = aPosition; vUv = aUv; }\n");
        int fragment = 0;
        int linked = 0;
        try {
            fragment = compileShader(GLES20.GL_FRAGMENT_SHADER,
                    "#extension GL_OES_EGL_image_external : require\n"
                            + "precision mediump float;\nvarying vec2 vUv;\n"
                            + "uniform samplerExternalOES uTexture;\nuniform float uOpacity;\n"
                            + "uniform vec3 uGrade; uniform vec2 uBalance; uniform float uZebra;\n"
                            + "void main() {\n"
                            + "vec3 c = texture2D(uTexture, vUv).rgb * uGrade.x;\n"
                            + "c *= vec3(1.0 + uBalance.x * 0.2, 1.0, 1.0 - uBalance.x * 0.2);\n"
                            + "c *= vec3(1.0 + uBalance.y * 0.1, 1.0 - uBalance.y * 0.2, 1.0 + uBalance.y * 0.1);\n"
                            + "c = (c - 0.5) * uGrade.y + 0.5;\n"
                            + "float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));\n"
                            + "c = clamp(mix(vec3(luma), c, uGrade.z), 0.0, 1.0);\n"
                            + "if (uZebra > 0.5 && max(c.r, max(c.g, c.b)) >= 0.95) {\n"
                            + "float stripe = step(6.0, mod(gl_FragCoord.x + gl_FragCoord.y, 12.0));\n"
                            + "c = mix(c, vec3(1.0, 0.85, 0.1), stripe * 0.85); }\n"
                            + "gl_FragColor = vec4(c, uOpacity); }\n");
            linked = GLES20.glCreateProgram();
            GLES20.glAttachShader(linked, vertex);
            GLES20.glAttachShader(linked, fragment);
            GLES20.glLinkProgram(linked);
            int[] status = new int[1];
            GLES20.glGetProgramiv(linked, GLES20.GL_LINK_STATUS, status, 0);
            if( status[0] == 0 )
                throw new RenderException("Camera program link failed: " + GLES20.glGetProgramInfoLog(linked));
            checkGl("link camera program");
            return linked;
        }
        catch(RuntimeException e) {
            if( linked != 0 )
                GLES20.glDeleteProgram(linked);
            throw e;
        }
        finally {
            GLES20.glDeleteShader(vertex);
            if( fragment != 0 )
                GLES20.glDeleteShader(fragment);
        }
    }

    private static int compileShader(int kind, String source) {
        int shader = GLES20.glCreateShader(kind);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if( status[0] == 0 ) {
            String message = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new RenderException("Camera shader compile failed: " + message);
        }
        return shader;
    }

    private static FloatBuffer floatBuffer(float[] values) {
        FloatBuffer buffer = ByteBuffer.allocateDirect(values.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        buffer.put(values).position(0);
        return buffer;
    }
}
