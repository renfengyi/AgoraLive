package io.agora.framework;

import android.opengl.EGLContext;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.faceunity.gles.core.EglCore;

import io.agora.framework.channels.ChannelManager;
import io.agora.framework.channels.VideoChannel;
import io.agora.framework.comsumers.IVideoConsumer;
import io.agora.rtc.mediaio.IVideoFrameConsumer;
import io.agora.rtc.mediaio.IVideoSource;
import io.agora.rtc.mediaio.MediaIO;
import io.agora.rtc.video.AgoraVideoFrame;

/**
 * The renderer acts as the consumer of the video source
 * from current video channel, and also the video source
 * of rtc engine.
 */
public class RtcVideoConsumer implements IVideoConsumer, IVideoSource {
    private static final String TAG = RtcVideoConsumer.class.getSimpleName();

    private volatile IVideoFrameConsumer mRtcConsumer;
    private volatile boolean mValidInRtc;

    private volatile VideoModule mVideoModule;
    private int mChannelId;

    private PushVideoFrameHandlerThread mPushThread;
    private Handler mPushHandler;

    public RtcVideoConsumer(VideoModule videoModule) {
        this(videoModule, ChannelManager.ChannelID.CAMERA);
    }

    private RtcVideoConsumer(VideoModule videoModule, int channelId) {
        mVideoModule = videoModule;
        mChannelId = channelId;
    }

    private static class PushVideoFrameHandlerThread extends HandlerThread {
        private EGLContext mEGLContext;
        private EglCore mEglCore;
        private EGLSurface mCurrentSurface;

        PushVideoFrameHandlerThread(String name) {
            super(name);
        }

        PushVideoFrameHandlerThread(String name, EGLContext eglContext) {
            this(name);
            mEGLContext = eglContext;
        }

        @Override
        public void run() {
            initOpenGLContext();
            super.run();
            releaseOpenGL();
        }

        private void initOpenGLContext() {
            mEglCore = new EglCore(mEGLContext, 0);
            mCurrentSurface = mEglCore.createOffscreenSurface(1, 1);
            mEglCore.makeCurrent(mCurrentSurface);
        }

        private void releaseOpenGL() {
            mEglCore.makeNothingCurrent();
            mEglCore.releaseSurface(mCurrentSurface);
            mEglCore.release();
        }
    }

    @Override
    public void onConsumeFrame(VideoCaptureFrame frame, VideoChannel.ChannelContext context) {
        if (mValidInRtc && mPushThread != null && mPushThread.isAlive()) {
            Log.i(TAG, "consume is called");
            mPushHandler.post(() -> {
                int format = frame.mFormat.getPixelFormat() == GLES20.GL_TEXTURE_2D
                        ? AgoraVideoFrame.FORMAT_TEXTURE_2D
                        : AgoraVideoFrame.FORMAT_TEXTURE_OES;
                long before = System.currentTimeMillis();
                if (mRtcConsumer != null) {
                    mRtcConsumer.consumeTextureFrame(frame.mTextureId,
                            format, frame.mFormat.getWidth(),
                            frame.mFormat.getHeight(), frame.mRotation,
                            frame.mTimeStamp, frame.mTexMatrix);
                }
                long after = System.currentTimeMillis();
                Log.i(TAG, "onConsumeFrame:" + (after - before) + " ms");
            });
        }
    }

    @Override
    public void connectChannel(int channelId) {
        // Rtc transmission is an off-screen rendering procedure.
        VideoChannel channel = mVideoModule.connectConsumer(
                this, channelId, IVideoConsumer.TYPE_OFF_SCREEN);
        mPushThread = new PushVideoFrameHandlerThread(TAG,
                channel.getChannelContext().getEglContext());
        mPushThread.start();
        mPushHandler = new Handler(mPushThread.getLooper());
    }

    @Override
    public void disconnectChannel(int channelId) {
        mVideoModule.disconnectConsumer(this, channelId);
        if (mPushThread != null && mPushThread.isAlive()) {
            mPushHandler.removeCallbacksAndMessages(null);
            mPushHandler = null;
            mPushThread.quit();
        }
    }

    @Override
    public Object onGetDrawingTarget() {
        // Rtc engine does not draw the frames
        // on any target window surface
        return null;
    }

    @Override
    public int onMeasuredWidth() {
        return 0;
    }

    @Override
    public int onMeasuredHeight() {
        return 0;
    }

    @Override
    public boolean onInitialize(IVideoFrameConsumer consumer) {
        Log.i(TAG, "onInitialize");
        mRtcConsumer = consumer;
        return true;
    }

    @Override
    public boolean onStart() {
        Log.i(TAG, "onStart");
        connectChannel(mChannelId);
        mValidInRtc = true;
        return true;
    }

    @Override
    public void onStop() {
        mValidInRtc = false;
        mRtcConsumer = null;
    }

    @Override
    public void onDispose() {
        Log.i(TAG , "onDispose");
        disconnectChannel(mChannelId);
    }

    @Override
    public int getBufferType() {
        return MediaIO.BufferType.TEXTURE.intValue();
    }
}