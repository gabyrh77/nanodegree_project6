/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.NodeApi;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MySunshineWatchFace extends CanvasWatchFaceService {
    private static final String TAG = MySunshineWatchFace.class.getSimpleName();
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private static final String WEATHER_PATH = "/weather";
    private static final String ID_KEY = "weather_id";
    private static final String LOW_KEY = "low_temp";
    private static final String HIGH_KEY = "high_temp";

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MySunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(MySunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MySunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener{
        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Bitmap mIconBitmap;
        Paint mTextTimePaint;
        Paint mTextTempPaint;
        boolean mAmbient;
        boolean mIsRound;
        Calendar mCalendar;

        GoogleApiClient mGoogleApiClient = new GoogleApiClient.Builder(MySunshineWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        float mXOffsetTime;
        float mYOffsetTime;
        float mYOffsetMinute;
        float mXOffsetIcon;
        float mYOffsetIcon;
        float mXOffsetLowTemp;
        float mYOffsetLowTemp;
        float mXOffsetHighTemp;
        float mYOffsetHighTemp;

        int mWeatherId = 800;
        double mLowTemp = 0;
        double mHighTemp = 0;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MySunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());
            Resources resources = MySunshineWatchFace.this.getResources();
            mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
            mYOffsetMinute = resources.getDimension(R.dimen.digital_y_offset_minute_ambient);
            mYOffsetIcon = resources.getDimension(R.dimen.digital_y_offset_icon);
            mYOffsetLowTemp = resources.getDimension(R.dimen.digital_y_offset_low_temp);
            mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.sunshine));

            mIconBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.art_clear);

            mTextTimePaint = new Paint();
            mTextTimePaint = createTextPaint(resources.getColor(R.color.primary_text));

            mTextTempPaint = new Paint();
            mTextTempPaint = createTextPaint(resources.getColor(R.color.secondary_text));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }
            super.onVisibilityChanged(visible);

            if (visible) {
                mGoogleApiClient.connect();
                registerReceiver();

                // Update time zone, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();

                if (mGoogleApiClient != null && mGoogleApiClient.isConnected()) {
                    Wearable.DataApi.removeListener(mGoogleApiClient, this);
                    mGoogleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            MySunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MySunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MySunshineWatchFace.this.getResources();
            mIsRound = insets.isRound();
            mXOffsetTime = resources.getDimension(mIsRound
                    ? R.dimen.digital_x_offset_round_time : R.dimen.digital_x_offset_time);
            mXOffsetIcon = resources.getDimension(mIsRound
                    ? R.dimen.digital_x_offset_round_icon : R.dimen.digital_x_offset_icon);
            mXOffsetLowTemp = resources.getDimension(mIsRound
                    ? R.dimen.digital_x_offset_round_low_temp : R.dimen.digital_x_offset_low_temp);
            mXOffsetHighTemp = resources.getDimension(mIsRound
                    ? R.dimen.digital_x_offset_round_high_temp : R.dimen.digital_x_offset_high_temp);

            mTextTimePaint.setTextSize(resources.getDimension(R.dimen.time_text_size));
            mTextTempPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                Resources resources = MySunshineWatchFace.this.getResources();
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextTimePaint.setAntiAlias(!inAmbientMode);
                    mTextTempPaint.setAntiAlias(!inAmbientMode);
                }

                float textSizeTime = resources.getDimension(mAmbient
                        ? R.dimen.time_text_size_ambient : R.dimen.time_text_size);
                float textSizeTemp = resources.getDimension(mAmbient
                        ? R.dimen.time_text_size : R.dimen.temp_text_size);
                mTextTimePaint.setTextSize(textSizeTime);
                mTextTempPaint.setTextSize(textSizeTemp);
                mTextTempPaint.setColor(resources.getColor(mAmbient ? R.color.primary_text:R.color.secondary_text));

                if (mAmbient) {
                    mXOffsetTime = resources.getDimension(mIsRound
                            ? R.dimen.digital_x_offset_round_time_ambient : R.dimen.digital_x_offset_time);
                    mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time_ambient);
                    mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp_ambient);
                    mXOffsetHighTemp = resources.getDimension(mIsRound ? R.dimen.digital_x_offset_round_high_temp_ambient:
                                                                         R.dimen.digital_x_offset_high_temp_ambient);
                }else {
                    mXOffsetTime = resources.getDimension(mIsRound
                            ? R.dimen.digital_x_offset_round_time : R.dimen.digital_x_offset_time);
                    mYOffsetTime = resources.getDimension(R.dimen.digital_y_offset_time);
                    mYOffsetHighTemp = resources.getDimension(R.dimen.digital_y_offset_high_temp);
                    mXOffsetHighTemp = resources.getDimension(mIsRound ? R.dimen.digital_x_offset_round_high_temp:
                                                                         R.dimen.digital_x_offset_high_temp);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            boolean is24Hour = DateFormat.is24HourFormat(MySunshineWatchFace.this);
            int hour = is24Hour ? mCalendar.get(Calendar.HOUR_OF_DAY): mCalendar.get(Calendar.HOUR);
            if (!is24Hour && hour == 0) {
                hour = 12;
            }

            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                mBackgroundPaint.setColor(getResources().getColor(Utility.getColorBackgroundByTime(mCalendar.get(Calendar.HOUR_OF_DAY))));
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
                canvas.drawBitmap(mIconBitmap, mXOffsetIcon, mYOffsetIcon, mBackgroundPaint);
            }
            Locale locale = Locale.getDefault();
            String highTempText = String.format(locale, "%.0f", mHighTemp) + "\u00B0";

            if (!mAmbient) {
                String text = String.format(locale, "%02d:%02d:%02d", hour, mCalendar.get(Calendar.MINUTE), mCalendar.get(Calendar.SECOND));
                canvas.drawText(text, mXOffsetTime, mYOffsetTime, mTextTimePaint);
                String lowTempText = String.format(locale, "%.0f", mLowTemp) + "\u00B0";
                canvas.drawText(lowTempText, mXOffsetLowTemp, mYOffsetLowTemp, mTextTempPaint);
                canvas.drawText(highTempText, mXOffsetHighTemp, mYOffsetHighTemp, mTextTimePaint);
            } else {
                String textHour = String.format(locale, "%02d", hour);
                canvas.drawText(textHour, mXOffsetTime, mYOffsetTime, mTextTimePaint);
                String textMinute = String.format(locale, "%02d", mCalendar.get(Calendar.MINUTE));
                canvas.drawText(textMinute, mXOffsetTime, mYOffsetMinute, mTextTimePaint);
                canvas.drawText(highTempText, mXOffsetHighTemp, mYOffsetHighTemp, mTextTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                Log.d(TAG, "Event received: " + event.getDataItem().getUri());
                if (event.getDataItem().getUri().getPath().equals(WEATHER_PATH)) {
                    DataMapItem dataMapItem = DataMapItem.fromDataItem(event.getDataItem());
                    updateUiWithDataMap(dataMapItem.getDataMap());
                }
            }
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
            fetchDataMap();
        }

        @Override  // GoogleApiClient.ConnectionCallbacks
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override  // GoogleApiClient.OnConnectionFailedListener
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }

        private void updateUiWithDataMap(DataMap dataMap) {
            mWeatherId = dataMap.getInt(ID_KEY);
            int weatherResource = Utility.getArtResourceForWeatherCondition(mWeatherId);
            if (weatherResource != -1) {
                mIconBitmap = BitmapFactory.decodeResource(getResources(), weatherResource);
            }
            mLowTemp = dataMap.getDouble(LOW_KEY);
            mHighTemp = dataMap.getDouble(HIGH_KEY);
            invalidate();
        }

        private void fetchDataMap() {
            Log.d(TAG, "fetchDataMap");
            Wearable.NodeApi.getLocalNode(mGoogleApiClient).setResultCallback(
                    new ResultCallback<NodeApi.GetLocalNodeResult>() {
                        @Override
                        public void onResult(NodeApi.GetLocalNodeResult getLocalNodeResult) {
                            Log.d(TAG, "fetchDataMap onResult");
                            String localNode = getLocalNodeResult.getNode().getId();
                            Uri uri = new Uri.Builder().scheme("wear").path(WEATHER_PATH)
                                    .authority(localNode)
                                    .build();
                            Wearable.DataApi.getDataItem(mGoogleApiClient, uri).setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                                @Override
                                public void onResult(DataApi.DataItemResult dataItemResult) {
                                    Log.d(TAG, "getDataItem onResult: " + dataItemResult.getStatus().getStatusMessage());
                                    if (dataItemResult.getStatus().isSuccess()) {
                                        if (dataItemResult.getDataItem() != null) {
                                            Log.d(TAG, "Event received on fetch: " + dataItemResult.getDataItem().getUri());
                                            DataItem configDataItem = dataItemResult.getDataItem();
                                            DataMapItem dataMapItem = DataMapItem.fromDataItem(configDataItem);
                                            updateUiWithDataMap(dataMapItem.getDataMap());
                                        }
                                    }
                                }
                            });
                        }
                    }
            );
        }
    }
}
