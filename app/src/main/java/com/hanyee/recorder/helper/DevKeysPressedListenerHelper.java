package com.hanyee.recorder.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.text.TextUtils;
import android.util.Log;


public class DevKeysPressedListenerHelper {

    private static final String TAG = "DevKeysPressedListener";

    private Context mContext;
    private OnDevKeysPressedListener mListener;
    private DevKeysPressedReceiver mReceiver;

    public interface OnDevKeysPressedListener {
        void onHomePressed();

        void onHomeLongPressed();

        void onLockScreen();
    }

    public DevKeysPressedListenerHelper(Context context) {
        mContext = context;
    }

    public void setOnDevKeysPressedListener(OnDevKeysPressedListener listener) {
        mListener = listener;
        mReceiver = new DevKeysPressedReceiver();
    }

    public void startWatch() {
        if (mReceiver != null) {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            mContext.registerReceiver(mReceiver, filter);
        }
    }

    public void stopWatch() {
        if (mReceiver != null) {
            mContext.unregisterReceiver(mReceiver);
        }
    }

    private class DevKeysPressedReceiver extends BroadcastReceiver {
        private static final String SYSTEM_DIALOG_REASON_KEY            = "reason";
        private static final String SYSTEM_DIALOG_REASON_GLOBAL_ACTIONS = "globalactions";
        private static final String SYSTEM_DIALOG_REASON_RECENT_APPS    = "recentapps";
        private static final String SYSTEM_DIALOG_REASON_HOME_KEY       = "homekey";
        private static final String SYSTEM_DIALOG_REASON_LOCK           = "lock";
        private static final String SYSTEM_DIALOG_REASON_ASSIST         = "assist";

        @Override
        public void onReceive(Context context, Intent intent) {

            String action = intent.getAction();

            if (TextUtils.equals(action, Intent.ACTION_CLOSE_SYSTEM_DIALOGS)) {

                String reason = intent.getStringExtra(SYSTEM_DIALOG_REASON_KEY);

                Log.i(TAG, "action:" + action + ",reason:" + reason);

                if (mListener != null) {
                    if (TextUtils.equals(SYSTEM_DIALOG_REASON_HOME_KEY, reason)) {

                        mListener.onHomePressed();

                    } else if (TextUtils.equals(SYSTEM_DIALOG_REASON_RECENT_APPS, reason) || SYSTEM_DIALOG_REASON_ASSIST.equals(reason)) {

                        mListener.onHomeLongPressed();

                    } else if (TextUtils.equals(SYSTEM_DIALOG_REASON_LOCK, reason)) {

                        mListener.onLockScreen();

                    }
                }
            } else if (TextUtils.equals(action, Intent.ACTION_SCREEN_OFF)) {

                mListener.onLockScreen();

            }
        }
    }
}
