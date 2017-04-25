
package com.hanyee.recorder.ui;

import android.content.Context;
import android.graphics.Point;
import android.net.Uri;
import android.os.SystemClock;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Display;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Chronometer;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.hanyee.recorder.helper.DevKeysPressedListenerHelper;
import com.hanyee.recorder.R;
import com.hanyee.recorder.helper.WakeLockHelper;

import org.androidannotations.annotations.Click;
import org.androidannotations.annotations.EActivity;
import org.androidannotations.annotations.ViewById;
import org.androidannotations.annotations.WindowFeature;

import java.util.List;


@EActivity
@WindowFeature(Window.FEATURE_NO_TITLE)
public class RecorderActivity extends AppCompatActivity {

    private final static String TAG = "RecorderActivity";

    @ViewById(R.id.surface_view)
    SurfaceView mPreview;
    @ViewById(R.id.button_flash)
    Button mFlashButton;
    @ViewById(R.id.button_capture)
    Button mCaptureButton;
    @ViewById(R.id.button_recorder)
    Button mRecordButton;
    @ViewById(R.id.time)
    Chronometer mTimer;
    @ViewById(R.id.previewPager)
    ViewPager mPreviewPager;
    @ViewById(R.id.loading_parent)
    ViewGroup mLoading;
    @ViewById(R.id.showLayer)
    LinearLayout mShowLayer;

    private RecordControl mRecordControl;
    private DevKeysPressedListenerHelper mDevKeysPressedListenerHelper;
    private PreviewPictureAdapter mPreviewAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        registerDevKeysPressedListener();
    }

    @Override
    protected void onResume() {
        super.onResume();
        WakeLockHelper.acquire(this, TAG);
        // 使用SurfaceView 当home键或者退出首屏 再次返回时重新构建ContentView避免Crash
        setContentView(R.layout.activity_record);
        setupCamera();
    }

    private void setupCamera() {
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        Point imageRect = new Point();
        display.getSize(imageRect);

        mRecordControl = new RecordControl();
        mRecordControl.setImageSize(imageRect.x, imageRect.y);
        mRecordControl.init(this, new RecordControl.TakePictureCallback() {
                    @Override
                    public void success(Uri filePath) {
                        mLoading.setVisibility(View.GONE);
                        Toast.makeText(RecorderActivity.this, R.string.recorder_toast_capture_done, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void failure(String errorMsg) {
                        mLoading.setVisibility(View.GONE);
                        Toast.makeText(RecorderActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                    }
                },

                new RecordControl.SaveRecordCallback() {
                    @Override
                    public void success(Uri filePath) {
                        Toast.makeText(RecorderActivity.this, R.string.recorder_toast_record_done, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void failure(String errorMsg) {
                        Toast.makeText(RecorderActivity.this, errorMsg, Toast.LENGTH_SHORT).show();
                    }
                });

        mPreview.getHolder().addCallback(mRecordControl);
        mPreview.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mRecordControl.autoFocusByTouch(event);
                return false;
            }
        });
    }

    @Click(R.id.button_capture)
    public void onTakePicture() {
        mLoading.setVisibility(View.VISIBLE);
        mRecordControl.takingPicture();
    }

    @Click(R.id.button_recorder)
    public void onRecorderVideo(View view) {
        if (mRecordControl.isVideoRecording()) {
            stopRecording();
            ((TextView) view).setText(R.string.recorder_btn_recorder);
        } else {
            startRecording();
            ((TextView) view).setText(R.string.recorder_btn_save);
        }
    }

    @Click(R.id.button_flash)
    public void onFlashLightAction() {
        mRecordControl.toggleFlashLight();
        mFlashButton.setText(mRecordControl.isFlashLightOn() ? R.string.recorder_btn_flash_off
                : R.string.recorder_btn_flash_on);
    }

    @Click(R.id.button_preview)
    public void onPreviewPictures() {
        if (mPreviewPager.getVisibility() == View.GONE) {
            List<Uri> preloadPhotos = mRecordControl.getPreloadPhotos();
            if (preloadPhotos == null || preloadPhotos.isEmpty()) {
                Toast.makeText(this, R.string.recorder_toast_preview_picture_failed, Toast.LENGTH_SHORT).show();
            } else {
                mPreviewPager.setVisibility(View.VISIBLE);
                if (mPreviewAdapter == null) {
                    mPreviewAdapter = new PreviewPictureAdapter(getFragmentManager(), preloadPhotos);
                    mPreviewPager.setOffscreenPageLimit(3);
                    mPreviewPager.setAdapter(mPreviewAdapter);
                } else {
                    mPreviewAdapter.setFilePaths(preloadPhotos);
                }
                mShowLayer.setBackgroundResource(R.drawable.background_transparent_gray);
                mShowLayer.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        mShowLayer.setClickable(false);
                        mPreviewPager.setVisibility(View.GONE);
                        mShowLayer.setBackgroundResource(R.drawable.background_transparent);
                    }
                });
            }
        } else {
            mPreviewPager.setVisibility(View.GONE);
        }
    }

    @Override
    public void onBackPressed() {
        // If recording, disable back key
        if (mRecordControl != null &&
                mRecordControl.isVideoRecording()) {
            Toast.makeText(this, R.string.recorder_toast_return_failed, Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        WakeLockHelper.release();
        mDevKeysPressedListenerHelper.stopWatch();
    }

    private void startRecording() {
        mRecordControl.startRecording();
        mRecordButton.setText(R.string.recorder_btn_save);
        mTimer.setBase(SystemClock.elapsedRealtime());
        mTimer.setVisibility(View.VISIBLE);
        mTimer.start();
    }

    private void stopRecording() {
        mRecordControl.stopRecording();
        mPreviewPager.setVisibility(View.GONE);
        mTimer.setVisibility(View.GONE);
        mTimer.stop();
    }

    private void registerDevKeysPressedListener() {
        mDevKeysPressedListenerHelper = new DevKeysPressedListenerHelper(this);
        mDevKeysPressedListenerHelper.setOnDevKeysPressedListener(new DevKeysPressedListenerHelper.OnDevKeysPressedListener() {

            @Override
            public void onHomePressed() {
                stopRecording();
            }

            @Override
            public void onHomeLongPressed() {
                stopRecording();
            }

            @Override
            public void onLockScreen() {
                stopRecording();
            }
        });
        mDevKeysPressedListenerHelper.startWatch();
    }
}
