package com.example.android.sunshine.app.sync;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.util.concurrent.TimeUnit;

public class SunshineSyncWearService extends IntentService {
    public static final String TAG = "SunshineSyncWearService";
    private static final String[] NOTIFY_WEATHER_PROJECTION = new String[] {
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_SHORT_DESC
    };

    // these indices must match the projection

    private static final int INDEX_WEATHER_ID = 0;
    private static final int INDEX_MAX_TEMP = 1;
    private static final int INDEX_MIN_TEMP = 2;

    private GoogleApiClient mGoogleApiClient;
    private static final int TIMEOUT_MS = 500;
    private static final String WEATHER_PATH = "/weather";
    private static final String ID_KEY = "weather_id";
    private static final String LOW_KEY = "low_temp";
    private static final String HIGH_KEY = "high_temp";

    public SunshineSyncWearService(){
        super(TAG);

    }

    @Override
    protected void onHandleIntent(Intent intent) {
        mGoogleApiClient = new GoogleApiClient.Builder(this)
                .addApi(Wearable.API)
                .build();
        String locationQuery = Utility.getPreferredLocation(this);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());

        // we'll query our contentProvider, as always
        Cursor cursor = this.getContentResolver().query(weatherUri, NOTIFY_WEATHER_PROJECTION, null, null, null);

        if (cursor!=null && cursor.moveToFirst()) {
            int weatherId = cursor.getInt(INDEX_WEATHER_ID);
            double high = cursor.getDouble(INDEX_MAX_TEMP);
            double low = cursor.getDouble(INDEX_MIN_TEMP);

            ConnectionResult result = mGoogleApiClient.blockingConnect(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            Log.d(TAG, "Google API result: " + result);

            if (!mGoogleApiClient.isConnected()) {
                Log.d(TAG, "Google API not connected");
                return;
            }

            //create data item
            PutDataMapRequest dataMap = PutDataMapRequest.create(WEATHER_PATH);
            dataMap.getDataMap().putInt(ID_KEY, weatherId);
            dataMap.getDataMap().putDouble(HIGH_KEY, high);
            dataMap.getDataMap().putDouble(LOW_KEY, low);
            PutDataRequest request = dataMap.asPutDataRequest();
            request.setUrgent();

            //send data to wearable
            DataApi.DataItemResult dataItemResult = Wearable.DataApi
                    .putDataItem(mGoogleApiClient, request).await();

            Log.d (TAG, dataItemResult.toString());
            Log.d (TAG, dataItemResult.getStatus().getStatusMessage());

            mGoogleApiClient.disconnect();
        }

        if (cursor!=null)
            cursor.close();
    }
}
