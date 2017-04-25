package com.hanyee.recorder.ui;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.hardware.Camera;
import android.location.LocationManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;

import com.hanyee.recorder.helper.CameraHelper;
import com.hanyee.recorder.ImageInfo;
import com.hanyee.recorder.helper.PictureUtils;
import com.hanyee.recorder.R;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RecordControl implements SurfaceHolder.Callback, Camera.PreviewCallback, Camera.AutoFocusCallback {

    private static final String TAG = "RecordControl";

    private static final int RECORDER_PARAMS_FRAME_RATE             = 30;
    private static final int RECORDER_PARAMS_RECORD_LENGTH          = 0;
    private static final int RECORDER_PARAMS_SAMPLE_AUDIO_RATE_INHZ = 44100;

    private static final String RECORDER_PARAMS_VIDEO_FORMAT = "mp4";
    private static final String RECORDER_PARAMS_VIDEO_ROTATE = "rotate";
    private static final String RECORDER_PARAMS_ROTATE_VALUE = "90";

    private static final int MSG_PROCESS_FRAME_DATA = 0;
    private static final int MSG_START_RECORDING    = 1;
    private static final int MSG_STOP_RECORDING     = 2;

    private Camera mCamera;
    private int mImageWidth, mImageHeight;
    private boolean mTakingPicture;
    private boolean mSavingFrameData;
    private boolean mIsFlashLightOn;
    private String mVideoFilePath;
    private boolean mIsPreviewOn;
    private long mStartTime;
    private double[] mLocation;
    private long mpictureTimePointer;
    private LocationManager mLocationManager;
    // frame record
    private int mFramesIndex;
    private Frame[] mFrames;
    private Frame mFrame;
    private long[] mFrameTimestamps;
    private FFmpegFrameRecorder mVideoRecorder;
    private boolean mIsVideoRecording;
    // audio record
    private int mSamplesIndex;
    private ShortBuffer[] mSamples;
    private Thread mAudioThread;
    private AudioRecordRunnable mAudioRecordRunnable;
    private AudioRecord mAudioRecord;
    private boolean mIsAudioRecording;
    // Callback
    private TakePictureCallback mTakePictureCallback;
    private SaveRecordCallback mSaveRecordCallback;
    private Context mContext;

    private List<Uri> mPreviewPhotoUris;
    private List<ImageInfo> mPhotoInfo;
    private Handler mHandler;
    private HandlerThread mHandlerThread;

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        Log.d(TAG, "surfaceCreated");

        if (mCamera == null) {
            mCamera = CameraHelper.getDefaultCameraInstance();
        }

        if (mHandler == null) {
            mHandlerThread = new HandlerThread("saveFrameData");
            mHandlerThread.start();
            mHandler = new Handler(mHandlerThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_PROCESS_FRAME_DATA:
                            processFrameData(msg.obj);
                            break;
                        case MSG_START_RECORDING:
                            doStartRecording();
                            break;
                        case MSG_STOP_RECORDING:
                            doStopRecording();
                            break;
                    }
                }
            };
        }

        try {
            mCamera.setPreviewDisplay(holder);
            mCamera.setPreviewCallback(this);
        } catch (IOException e) {
            e.printStackTrace();
            mCamera.release();
            mCamera = null;
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        Log.d(TAG, "surfaceChanged width=" + width + " height=" + height);

        // If your preview can change or rotate, take care of those events here.
        // Make sure to stop the preview before resizing or reformatting it.
        if (mCamera == null || holder.getSurface() == null) {
            // preview surface does not exist
            return;
        }

        // stop preview before making changes
        stopPreview();

        // make some new changes
        initCameraProfile(width, height);

        // start preview with new settings
        try {
            mCamera.setPreviewDisplay(holder);
            mCamera.cancelAutoFocus();
            startPreview();
            mCamera.autoFocus(this);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d(TAG, "surfaceDestroyed");

        if (holder != null) {
            holder.removeCallback(this);
        }

        stopPreview();

        if (mCamera != null) {
            mCamera.cancelAutoFocus();
            mCamera.setPreviewCallback(null);
            mCamera.release();
            mCamera = null;
        }

        if (mPhotoInfo != null)
            mPhotoInfo.clear();
        if (mPreviewPhotoUris != null)
            mPreviewPhotoUris.clear();

        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
        }

        if (mHandler != null) {
            mHandler.removeCallbacksAndMessages(null);
            mHandler = null;
        }

        mPhotoInfo = null;
        mPreviewPhotoUris = null;
        mTakePictureCallback = null;
        mSaveRecordCallback = null;
        mLocationManager = null;
    }

    public void startPreview() {
        if (!mIsPreviewOn && mCamera != null) {
            mIsPreviewOn = true;
            mCamera.startPreview();
        }
    }

    public void stopPreview() {
        if (mIsPreviewOn && mCamera != null) {
            mIsPreviewOn = false;
            mCamera.stopPreview();
        }
    }

    @Override
    public void onPreviewFrame(byte[] data, Camera camera) {
        // Saving the frame data in sub-thread avoid to block UI thread,
        // and only send saving message after one saving task being done
        // to avoid OOM.
        if (!mSavingFrameData) {
            Log.d(TAG, "Saving preview frame data ...");
            mSavingFrameData = true;
            mHandler.obtainMessage(MSG_PROCESS_FRAME_DATA, data).sendToTarget();
        }
    }

    private void processFrameData(Object param) {
        if (param == null) {
            Log.e(TAG, "Preview frame data is NULL!");
            return;
        }

        byte[] data = (byte[]) param;

        // Saving video frame data by recorder
        savingVideoFrameData(data);

        // Saving video frame data to jpeg file
        if (mTakingPicture) {
            new SaveCapturePicTask().execute(data);
            mTakingPicture = false;
        } else {
            mSavingFrameData = false;
        }

    }

    @Override
    public void onAutoFocus(boolean success, Camera camera) {
        Log.d(TAG, "onAutoFocus success=" + success);
    }

    public void autoFocusByTouch(MotionEvent event) {
        Rect focusRect = calculateTapArea(event.getRawX(), event.getRawY(), 1f);
        Rect meteringRect = calculateTapArea(event.getRawX(), event.getRawY(), 1.5f);

        Camera.Parameters parameters = mCamera.getParameters();

        if (parameters.getMaxNumFocusAreas() > 0) {
            List<Camera.Area> focusAreas = new ArrayList<Camera.Area>();
            focusAreas.add(new Camera.Area(focusRect, 1000));
            parameters.setFocusAreas(focusAreas);
        }

        if (parameters.getMaxNumMeteringAreas() > 0) {
            List<Camera.Area> meteringAreas = new ArrayList<Camera.Area>();
            meteringAreas.add(new Camera.Area(meteringRect, 1000));
            parameters.setMeteringAreas(meteringAreas);
        }

        mCamera.autoFocus(this);
    }

    /**
     * Convert touch position x:y to {@link Camera.Area} position -1000:-1000 to 1000:1000.
     */
    private Rect calculateTapArea(float x, float y, float coefficient) {
        float focusAreaSize = 300;
        int areaSize = Float.valueOf(focusAreaSize * coefficient).intValue();

        int centerX = (int) (x / mCamera.getParameters().getPreviewSize().width * 2000 - 1000);
        int centerY = (int) (y / mCamera.getParameters().getPreviewSize().height * 2000 - 1000);

        int left = clamp(centerX - areaSize / 2, -1000, 1000);
        int right = clamp(left + areaSize, -1000, 1000);
        int top = clamp(centerY - areaSize / 2, -1000, 1000);
        int bottom = clamp(top + areaSize, -1000, 1000);

        return new Rect(left, top, right, bottom);
    }

    private int clamp(int x, int min, int max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    public void init(Context context, TakePictureCallback takePictureCallback,
                     SaveRecordCallback saveRecordCallback) {
        Log.d(TAG, "init");
        mContext = context;
        mTakePictureCallback = takePictureCallback;
        mSaveRecordCallback = saveRecordCallback;

        mPreviewPhotoUris = new ArrayList<Uri>();

        new Thread(new Runnable() {
            @Override
            public void run() {
                readImages();
            }
        }).start();
    }

    private void readImages() {
        if (mPhotoInfo != null && !mPhotoInfo.isEmpty()) {
            for (ImageInfo info : mPhotoInfo) {
                mPreviewPhotoUris.add(Uri.parse(info.getImageIndex()));
            }
        } else {
            mPhotoInfo = new ArrayList<ImageInfo>();
        }
    }

    public List<Uri> getPreloadPhotos() {
        return mPreviewPhotoUris;
    }

    public List<ImageInfo> getPhotoInfo() {
        return mPhotoInfo;
    }

    public boolean isVideoRecording() {
        return mIsVideoRecording;
    }

    public void startRecording() {
        Log.d(TAG, "startRecording");
        if (mHandler != null)
            mHandler.obtainMessage(MSG_START_RECORDING).sendToTarget();
    }

    private void doStartRecording() {
        if (mIsVideoRecording) {
            return;
        }
        if (RECORDER_PARAMS_RECORD_LENGTH > 0) {
            mFramesIndex = 0;
            mFrames = new Frame[RECORDER_PARAMS_RECORD_LENGTH * RECORDER_PARAMS_FRAME_RATE];
            mFrameTimestamps = new long[mFrames.length];
            for (int i = 0; i < mFrames.length; i++) {
                mFrames[i] = new Frame(mImageWidth, mImageHeight, Frame.DEPTH_UBYTE, 2);
                mFrameTimestamps[i] = -1;
            }
        } else if (mFrame == null) {
            mFrame = new Frame(mImageWidth, mImageHeight, Frame.DEPTH_UBYTE, 2);
            Log.d(TAG, "create frame");
        }

        mVideoFilePath = CameraHelper.getOutputMediaFile(CameraHelper.MEDIA_TYPE_VIDEO, "").getPath();
        mVideoRecorder = new FFmpegFrameRecorder(mVideoFilePath, mImageWidth, mImageHeight, 1);
        mVideoRecorder.setFormat(RECORDER_PARAMS_VIDEO_FORMAT);
        mVideoRecorder.setFrameRate(RECORDER_PARAMS_FRAME_RATE);
        mVideoRecorder.setSampleRate(RECORDER_PARAMS_SAMPLE_AUDIO_RATE_INHZ);
        mVideoRecorder.setVideoMetadata(RECORDER_PARAMS_VIDEO_ROTATE, RECORDER_PARAMS_ROTATE_VALUE);

        Log.d(TAG, "mVideoRecorder initialize success");

        try {
            mVideoRecorder.start();
            mIsVideoRecording = true;
            mStartTime = System.currentTimeMillis();

            mAudioRecordRunnable = new AudioRecordRunnable();
            mAudioThread = new Thread(mAudioRecordRunnable);
            mAudioThread.start();
            mIsAudioRecording = true;
        } catch (FFmpegFrameRecorder.Exception e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            e.printStackTrace();
            mSaveRecordCallback.failure(mContext.getString(R.string.app_recorder_permission_exception_notice));
        }
    }

    public void stopRecording() {
        Log.d(TAG, "stopRecording");
        if (mHandler != null)
            mHandler.obtainMessage(MSG_STOP_RECORDING).sendToTarget();
    }

    private void doStopRecording() {
        if (!mIsVideoRecording) {
            return;
        }
        mIsAudioRecording = false;
        try {
            mAudioThread.join();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        mAudioRecordRunnable = null;
        mAudioThread = null;

        if (mVideoRecorder != null && mIsVideoRecording) {
            if (RECORDER_PARAMS_RECORD_LENGTH > 0) {
                Log.d(TAG, "Writing frames");
                try {
                    int firstIndex = mFramesIndex % mSamples.length;
                    int lastIndex = (mFramesIndex - 1) % mFrames.length;
                    if (mFramesIndex <= mFrames.length) {
                        firstIndex = 0;
                        lastIndex = mFramesIndex - 1;
                    }
                    if ((mStartTime = mFrameTimestamps[lastIndex] - RECORDER_PARAMS_RECORD_LENGTH * 1000000L) < 0) {
                        mStartTime = 0;
                    }
                    if (lastIndex < firstIndex) {
                        lastIndex += mFrames.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++) {
                        long t = mFrameTimestamps[i % mFrameTimestamps.length] - mStartTime;
                        if (t >= 0) {
                            if (t > mVideoRecorder.getTimestamp()) {
                                mVideoRecorder.setTimestamp(t);
                            }
                            mVideoRecorder.record(mFrames[i % mFrames.length]);
                        }
                    }

                    firstIndex = mSamplesIndex % mSamples.length;
                    lastIndex = (mSamplesIndex - 1) % mSamples.length;
                    if (mSamplesIndex <= mSamples.length) {
                        firstIndex = 0;
                        lastIndex = mSamplesIndex - 1;
                    }
                    if (lastIndex < firstIndex) {
                        lastIndex += mSamples.length;
                    }
                    for (int i = firstIndex; i <= lastIndex; i++) {
                        mVideoRecorder.recordSamples(mSamples[i % mSamples.length]);
                    }
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.e(TAG, e.getMessage());
                    e.printStackTrace();
                    if (mSaveRecordCallback != null) {
                        mSaveRecordCallback.failure(e.getMessage());
                    }
                }
            }

            mIsVideoRecording = false;
            Log.d(TAG, "Finishing isRecording, calling stop and release on mVideoRecorder");
            try {
                mVideoRecorder.stop();
                mVideoRecorder.release();
                if (mSaveRecordCallback != null) {
                    mSaveRecordCallback.success(Uri.parse(mVideoFilePath));
                }
                notifyMediaScanner(Uri.parse(mVideoFilePath));
            } catch (FFmpegFrameRecorder.Exception e) {
                e.printStackTrace();
                if (mSaveRecordCallback != null) {
                    mSaveRecordCallback.failure(e.getMessage());
                }
            }
            mVideoRecorder = null;
        }
    }

    public void takingPicture() {
        if (!mTakingPicture) {
            mTakingPicture = true;
        }
    }

    public boolean isFlashLightOn() {
        return mIsFlashLightOn;
    }

    public void toggleFlashLight() {
        if (mCamera == null) {
            return;
        }

        // toggle the flashlight
        mIsFlashLightOn = !mIsFlashLightOn;

        Camera.Parameters camParams = mCamera.getParameters();
        camParams.setFlashMode(mIsFlashLightOn
                ? Camera.Parameters.FLASH_MODE_TORCH
                : Camera.Parameters.FLASH_MODE_OFF);
        mCamera.setParameters(camParams);
    }

    public void setImageSize(int imageWidth, int imageHeight) {
        mImageWidth = imageWidth;
        mImageHeight = imageHeight;
    }

    private void savingVideoFrameData(byte[] data) {
        if (mAudioRecord == null
                || mAudioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            mStartTime = System.currentTimeMillis();
            return;
        }

        if (RECORDER_PARAMS_RECORD_LENGTH > 0) {
            int i = mFramesIndex++ % mFrames.length;
            mFrame = mFrames[i];
            mFrameTimestamps[i] = 1000 * (System.currentTimeMillis() - mStartTime);
        }
            /* get video data */
        if (mFrame != null && mIsVideoRecording) {
            ((ByteBuffer) mFrame.image[0].position(0)).put(data);

            if (RECORDER_PARAMS_RECORD_LENGTH <= 0) {
                try {
                    Log.d(TAG, "Writing Frame");
                    long t = 1000 * (System.currentTimeMillis() - mStartTime);
                    if (t > mVideoRecorder.getTimestamp()) {
                        mVideoRecorder.setTimestamp(t);
                    }
                    mVideoRecorder.record(mFrame);
                } catch (FFmpegFrameRecorder.Exception e) {
                    Log.d(TAG, e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    private void initCameraProfile(int surfaceChangedWidth, int surfaceChangedHeight) {

        // We need to make sure that our preview and recording video size are supported by the
        // camera. Query camera to find all the sizes and choose the optimal size given the
        // dimensions of our preview surface.
        Camera.Parameters parameters = mCamera.getParameters();
        List<Camera.Size> mPreviewSizes = parameters.getSupportedPreviewSizes();

        // Sort the list in ascending order
        Collections.sort(mPreviewSizes, new Comparator<Camera.Size>() {
            public int compare(final Camera.Size a, final Camera.Size b) {
                return a.width * a.height - b.width * b.height;
            }
        });

        // Pick the first preview size that is equal or bigger, or pick the last (biggest) option
        // if we cannot reach the initial settings of mImageWidth/mImageHeight.
        for (int i = 0; i < mPreviewSizes.size(); i++) {
            // Because we record the video in portrait, so we should exchange width/height
            // to compare them.
            if ((mPreviewSizes.get(i).width >= surfaceChangedHeight
                    && mPreviewSizes.get(i).height >= surfaceChangedWidth)
                    || i == mPreviewSizes.size() - 1) {
                mImageWidth = mPreviewSizes.get(i).width;
                mImageHeight = mPreviewSizes.get(i).height;
                Log.d(TAG, "Changed to supported resolution: " + mImageWidth + "x" + mImageHeight);
                break;
            }
        }
        parameters.setPreviewSize(mImageWidth, mImageHeight);

        int defaultCameraId = -1;
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO) {
            int numberOfCameras = Camera.getNumberOfCameras();
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            for (int i = 0; i < numberOfCameras; i++) {
                Camera.getCameraInfo(i, cameraInfo);
                if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    defaultCameraId = i;
                }
            }
        }
        // 系统版本为8以下的不支持这种对焦
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.FROYO) {
            mCamera.setDisplayOrientation(CameraHelper.determineDisplayOrientation(
                    mContext, defaultCameraId));
            List<String> focusModes = parameters.getSupportedFocusModes();
            if (focusModes != null && !focusModes.isEmpty()) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                    parameters.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
                } else {
                    parameters.setFocusMode(focusModes.get(0));
                }
            }
        } else {
            mCamera.setDisplayOrientation(90);
        }

        parameters.setPreviewFrameRate(30);
        parameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
        mCamera.setParameters(parameters);
    }

    private class AudioRecordRunnable implements Runnable {

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO);

            // Audio
            int bufferSize;
            ShortBuffer audioData;
            int bufferReadResult;

            bufferSize = AudioRecord.getMinBufferSize(RECORDER_PARAMS_SAMPLE_AUDIO_RATE_INHZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            mAudioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    RECORDER_PARAMS_SAMPLE_AUDIO_RATE_INHZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);

            if (RECORDER_PARAMS_RECORD_LENGTH > 0) {
                mSamplesIndex = 0;
                mSamples = new ShortBuffer[RECORDER_PARAMS_RECORD_LENGTH * RECORDER_PARAMS_SAMPLE_AUDIO_RATE_INHZ * 2 / bufferSize + 1];
                for (int i = 0; i < mSamples.length; i++) {
                    mSamples[i] = ShortBuffer.allocate(bufferSize);
                }
            } else {
                audioData = ShortBuffer.allocate(bufferSize);
            }

            Log.d(TAG, "mAudioRecord.start()");
            mAudioRecord.startRecording();

            /* ffmpeg_audio encoding loop */
            while (mIsAudioRecording) {
                if (RECORDER_PARAMS_RECORD_LENGTH > 0) {
                    audioData = mSamples[mSamplesIndex++ % mSamples.length];
                    audioData.position(0).limit(0);
                }
                bufferReadResult = mAudioRecord.read(audioData.array(), 0, audioData.capacity());
                audioData.limit(bufferReadResult);
                if (bufferReadResult > 0) {
                    // If "isRecording" isn't true when start this thread, it never get's set according to this if statement...!!!
                    // Why?  Good question...
                    if (mIsVideoRecording) {
                        if (RECORDER_PARAMS_RECORD_LENGTH <= 0) {
                            try {
                                mVideoRecorder.recordSamples(audioData);
                            } catch (FFmpegFrameRecorder.Exception e) {
                                Log.d(TAG, e.getMessage());
                                e.printStackTrace();
                            }
                        }
                    }
                }
            }
            Log.d(TAG, "AudioThread Finished, release mAudioRecord");

            /* encoding finish, release recorder */
            if (mAudioRecord != null) {
                mAudioRecord.stop();
                mAudioRecord.release();
                mAudioRecord = null;
                Log.d(TAG, "mAudioRecord released");
            }
        }
    }

    private class SaveCapturePicTask extends AsyncTask<byte[], Void, Uri> {
        @Override
        protected void onPreExecute() {
            mpictureTimePointer = System.currentTimeMillis() - mStartTime;
        }

        @Override
        protected Uri doInBackground(byte[]... params) {
            return PictureUtils.savePicture(params[0], mImageWidth, mImageHeight, CameraHelper.getOutputMediaFile(
                    CameraHelper.MEDIA_TYPE_IMAGE, getPictureTitleInfo()));
        }

        @Override
        protected void onPostExecute(Uri result) {
            if (result == null) {
                if (mTakePictureCallback != null) {
                    mTakePictureCallback.failure(mContext.getString(R.string.recorder_toast_capture_failed));
                }
            } else {
                if (mTakePictureCallback != null) {
                    mPreviewPhotoUris.add(result);
                    notifyMediaScanner(result);
                    mTakePictureCallback.success(result);
                }
                saveImageInfo(result);
            }
            mSavingFrameData = false;
        }
    }

    private void saveImageInfo(Uri uri) {
        ImageInfo info = new ImageInfo();
        info.setShowTime(String.valueOf(mpictureTimePointer));
        info.setImageIndex(uri.getPath());

        // Save photo info to memory
        if (mPhotoInfo != null) {
            mPhotoInfo.add(info);
        }
    }

    private void notifyMediaScanner(Uri filePath) {
        Intent intent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
        intent.setData(filePath);
        mContext.sendBroadcast(intent);
    }

    private String getPictureTitleInfo() {
        return "";
    }

    public interface TakePictureCallback {
        void success(Uri fileUri);

        void failure(String errorMsg);
    }

    public interface SaveRecordCallback {
        void success(Uri filePath);

        void failure(String errorMsg);
    }

}
