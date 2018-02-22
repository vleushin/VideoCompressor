package com.vincent.videocompressor;

import android.annotation.TargetApi;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

import static com.vincent.videocompressor.GLToolbox.checkGlError;

@TargetApi(16)
public class OutputSurface implements SurfaceTexture.OnFrameAvailableListener {

    private static final int EGL_OPENGL_ES2_BIT = 4;
    private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
    private EGL10 mEGL;
    private EGLDisplay mEGLDisplay = null;
    private EGLContext mEGLContext = null;
    private EGLSurface mEGLSurface = null;
    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;
    private final Object mFrameSyncObject = new Object();
    private boolean mFrameAvailable;
    private GhostTextureRenderer mGhostTextureRender;
    private TextureRenderer mTextureRender;
    private int mWidth;
    private int mHeight;
    private int rotateRender = 0;
    private ByteBuffer mPixelBuf;
    private Resources mResources;

    private int mGhostTextureId;

    public OutputSurface(Resources resources, int width, int height, int rotate) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException();
        }
        mResources = resources;
        mWidth = width;
        mHeight = height;
        rotateRender = rotate;
        mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4);
        mPixelBuf.order(ByteOrder.LITTLE_ENDIAN);
        eglSetup(width, height);
        makeCurrent();
        setup();
    }

    public OutputSurface(Resources resources, int width, int height) {
        mResources = resources;
        mWidth = width;
        mHeight = height;
        setup();
    }

    private void setup() {
        mGhostTextureRender = new GhostTextureRenderer();
        mGhostTextureRender.init();

        Bitmap ghostBitmap = BitmapFactory.decodeResource(mResources, R.drawable.ghost);
        int ghostImageWidth = ghostBitmap.getWidth();
        int ghostImageHeight = ghostBitmap.getHeight();
        mGhostTextureRender.updateTextureSize(ghostImageWidth, ghostImageHeight, mWidth, mHeight);
        mGhostTextureRender.updateViewSize(mWidth, mHeight);

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mGhostTextureId = textures[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGhostTextureId);
        checkGlError("glBindTexture mGhostTextureID");
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, ghostBitmap, 0);
        GLToolbox.initTexParams();
        checkGlError("glTexParameter");

        mTextureRender = new TextureRenderer(rotateRender);
        mTextureRender.surfaceCreated();
        mSurfaceTexture = new SurfaceTexture(mTextureRender.getTextureId());
        mSurfaceTexture.setOnFrameAvailableListener(this);
        mSurface = new Surface(mSurfaceTexture);
    }

    private void eglSetup(int width, int height) {
        mEGL = (EGL10) EGLContext.getEGL();
        mEGLDisplay = mEGL.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);

        if (mEGLDisplay == EGL10.EGL_NO_DISPLAY) {
            throw new RuntimeException("unable to get EGL10 display");
        }

        if (!mEGL.eglInitialize(mEGLDisplay, null)) {
            mEGLDisplay = null;
            throw new RuntimeException("unable to initialize EGL10");
        }

        int[] attribList = {
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!mEGL.eglChooseConfig(mEGLDisplay, attribList, configs, configs.length, numConfigs)) {
            throw new RuntimeException("unable to find RGB888+pbuffer EGL config");
        }
        int[] attrib_list = {
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL10.EGL_NONE
        };
        mEGLContext = mEGL.eglCreateContext(mEGLDisplay, configs[0], EGL10.EGL_NO_CONTEXT, attrib_list);
        checkEglError("eglCreateContext");
        if (mEGLContext == null) {
            throw new RuntimeException("null context");
        }
        int[] surfaceAttribs = {
                EGL10.EGL_WIDTH, width,
                EGL10.EGL_HEIGHT, height,
                EGL10.EGL_NONE
        };
        mEGLSurface = mEGL.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs);
        checkEglError("eglCreatePbufferSurface");
        if (mEGLSurface == null) {
            throw new RuntimeException("surface was null");
        }
    }

    public void release() {
        if (mEGL != null) {
            if (mEGL.eglGetCurrentContext().equals(mEGLContext)) {
                mEGL.eglMakeCurrent(mEGLDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
            }
            mEGL.eglDestroySurface(mEGLDisplay, mEGLSurface);
            mEGL.eglDestroyContext(mEGLDisplay, mEGLContext);
        }
        mSurface.release();
        mEGLDisplay = null;
        mEGLContext = null;
        mEGLSurface = null;
        mEGL = null;
        mGhostTextureRender = null;
        mTextureRender = null;
        mSurface = null;
        mSurfaceTexture = null;
    }

    public void makeCurrent() {
        if (mEGL == null) {
            throw new RuntimeException("not configured for makeCurrent");
        }
        checkEglError("before makeCurrent");
        if (!mEGL.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    public Surface getSurface() {
        return mSurface;
    }

    public void changeFragmentShader(String fragmentShader) {
        mTextureRender.changeFragmentShader(fragmentShader);
    }

    public void awaitNewImage() {
        final int TIMEOUT_MS = 5000;
        synchronized (mFrameSyncObject) {
            while (!mFrameAvailable) {
                try {
                    mFrameSyncObject.wait(TIMEOUT_MS);
                    if (!mFrameAvailable) {
                        throw new RuntimeException("Surface frame wait timed out");
                    }
                } catch (InterruptedException ie) {
                    throw new RuntimeException(ie);
                }
            }
            mFrameAvailable = false;
        }
        checkGlError("before updateTexImage");
        mSurfaceTexture.updateTexImage();
    }

    public void drawImage(boolean invert) {
        mTextureRender.drawFrame(mSurfaceTexture, invert);
        mGhostTextureRender.renderTexture(mGhostTextureId);
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (mFrameSyncObject) {
            if (mFrameAvailable) {
                throw new RuntimeException("mFrameAvailable already set, frame could be dropped");
            }
            mFrameAvailable = true;
            mFrameSyncObject.notifyAll();
        }
    }

    public ByteBuffer getFrame() {
        mPixelBuf.rewind();
        GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPixelBuf);
        return mPixelBuf;
    }

    private void checkEglError(String msg) {
        if (mEGL.eglGetError() != EGL10.EGL_SUCCESS) {
            throw new RuntimeException("EGL error encountered (see log)");
        }
    }
}
