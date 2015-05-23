package com.dexdrip.stephenblack.nightwatch;

import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.content.LocalBroadcastManager;
import android.support.wearable.view.WatchViewStub;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.*;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.ustwo.clockwise.ConnectedWatchFace;
import com.ustwo.clockwise.WatchFace;
import com.ustwo.clockwise.WatchFaceTime;
import com.ustwo.clockwise.WatchShape;
import lecho.lib.hellocharts.view.LineChartView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;

/**
 * Created by stephenblack on 12/29/14.
 */
public  abstract class BaseWatchFace extends ConnectedWatchFace {
    public static final String WEARABLE_DATA_PATH = "/nightscout_watch_data";
    public static final String WEARABLE_RESEND_PATH = "/nightscout_watch_data_resend";

    public static final long[] vibratePattern = {0,400,300,400,300,400};
    public TextView mTime, mSgv, mDirection, mTimestamp, mUploaderBattery, mDelta;
    public RelativeLayout mRelativeLayout;
    public LinearLayout mLinearLayout;
    public long sgvLevel = 0;
    public int batteryLevel = 1;
    public int ageLevel = 1;
    public int highColor = Color.YELLOW;
    public int lowColor = Color.RED;
    public int midColor = Color.WHITE;
    public int pointSize = 2;
    public boolean singleLine = false;
    public boolean layoutSet = false;
    public int missed_readings_alert_id = 818;
    public BgGraphBuilder bgGraphBuilder;
    public LineChartView chart;
    public double datetime;
    public ArrayList<BgWatchData> bgDataList = new ArrayList<>();
    public PowerManager.WakeLock wakeLock;
    // related to manual layout
    public View layoutView;
    private final Point displaySize = new Point();
    private int specW, specH;

    @Override
    public void onCreate() {
        super.onCreate();
        Display display = ((WindowManager) getSystemService(Context.WINDOW_SERVICE))
                .getDefaultDisplay();
        display.getSize(displaySize);
        wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Clock");

        specW = View.MeasureSpec.makeMeasureSpec(displaySize.x,
                View.MeasureSpec.EXACTLY);
        specH = View.MeasureSpec.makeMeasureSpec(displaySize.y,
                View.MeasureSpec.EXACTLY);
    }

    @Override
    protected void onLayout(WatchShape shape, Rect screenBounds, WindowInsets screenInsets) {
        super.onLayout(shape, screenBounds, screenInsets);
        layoutView.onApplyWindowInsets(screenInsets);
    }

    public void performViewSetup() {
        final WatchViewStub stub = (WatchViewStub) layoutView.findViewById(R.id.watch_view_stub);
        stub.setOnLayoutInflatedListener(new WatchViewStub.OnLayoutInflatedListener() {
            @Override
            public void onLayoutInflated(WatchViewStub stub) {
                mTime = (TextView) stub.findViewById(R.id.watch_time);
                mSgv = (TextView) stub.findViewById(R.id.sgv);
                mDirection = (TextView) stub.findViewById(R.id.direction);
                mTimestamp = (TextView) stub.findViewById(R.id.timestamp);
                mUploaderBattery = (TextView) stub.findViewById(R.id.uploader_battery);
                mDelta = (TextView) stub.findViewById(R.id.delta);
                mRelativeLayout = (RelativeLayout) stub.findViewById(R.id.main_layout);
                mLinearLayout = (LinearLayout) stub.findViewById(R.id.secondary_layout);
                chart = (LineChartView) stub.findViewById(R.id.chart);
                layoutSet = true;
                mRelativeLayout.measure(specW, specH);
                mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                        mRelativeLayout.getMeasuredHeight());
                WatchFaceTime startTime = new WatchFaceTime();
                startTime.set(0);
                onTimeChanged(startTime, new WatchFaceTime());
            }
        });
        wakeLock.acquire(50);
    }

    public void requestData() {
        putMessage(WEARABLE_RESEND_PATH, null, null);
    }

    public int ageLevel() {
        if(timeSince() <= (1000 * 60 * 12)) {
            return 1;
        } else {
            return 0;
        }
    }

    public double timeSince() {
        return new Date().getTime() - datetime;
    }

    public String readingAge() {
        if (datetime == 0) { return "-- Minute ago"; }
        int minutesAgo = (int) Math.floor(timeSince()/(1000*60));
        if (minutesAgo == 1) {
            return minutesAgo + " Minute ago";
        }
        return minutesAgo + " Minutes ago";
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if(layoutSet) {
            this.mRelativeLayout.draw(canvas);
            Log.d("onDraw", "draw");
            if (bgDataList.size() <= 2) {
                requestData();
            }
        }
        wakeLock.release();
    }

    @Override
    protected void onTimeChanged(WatchFaceTime oldTime, WatchFaceTime newTime) {
        if (layoutSet && (newTime.hasHourChanged(oldTime) || newTime.hasMinuteChanged(oldTime))) {
            wakeLock.acquire(50);
            final java.text.DateFormat timeFormat = DateFormat.getTimeFormat(BaseWatchFace.this);
            mTime.setText(timeFormat.format(Calendar.getInstance().getTime()));
            mTimestamp.setText(readingAge());
            missedReadingAlert();
            mRelativeLayout.measure(specW, specH);
            mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                    mRelativeLayout.getMeasuredHeight());
        }
    }
        @Override
        public void onDataChanged(DataEventBuffer dataEvents) {
            DataMap dataMap;

            for (DataEvent event : dataEvents) {

                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    String path = event.getDataItem().getUri().getPath();
                    if (path.equals(WEARABLE_DATA_PATH)) {
                        dataMap = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();
                        if (layoutSet) {
                            Log.d("MessageReciever: ", "Data received");
                            wakeLock.acquire(50);
                            sgvLevel = dataMap.getLong("sgvLevel");
                            batteryLevel = dataMap.getInt("batteryLevel");
                            datetime = dataMap.getDouble("timestamp");

                            mSgv.setText(dataMap.getString("sgvString"));
                            mDirection.setText(dataMap.getString("slopeArrow"));
                            mUploaderBattery.setText("Uploader: " + dataMap.getString("battery") + "%");
                            mDelta.setText(dataMap.getString("delta"));

                            mTimestamp.setText(readingAge());
                            if (chart != null) {
                                addToWatchSet(dataMap);
                                setupCharts();
                            }
                            mRelativeLayout.measure(specW, specH);
                            mRelativeLayout.layout(0, 0, mRelativeLayout.getMeasuredWidth(),
                                    mRelativeLayout.getMeasuredHeight());
                            invalidate();
                        } else {
                            Log.d("ERROR: ", "DATA IS NOT YET SET");
                        }
                        setColor();
                    }

                }
            }
        }

    public void setColor() { Log.e("ERROR: ", "MUST OVERRIDE IN CLASS"); }


    public void missedReadingAlert() {
        int minutes_since = (int) Math.floor(timeSince()/(1000*60));
        if(minutes_since >= 16 && ((minutes_since - 16) % 5) == 0) {
            NotificationCompat.Builder notification = new NotificationCompat.Builder(getApplicationContext())
                        .setContentTitle("Missed BG Readings")
                        .setVibrate(vibratePattern);
            NotificationManager mNotifyMgr = (NotificationManager) getApplicationContext().getSystemService(getApplicationContext().NOTIFICATION_SERVICE);
            mNotifyMgr.notify(missed_readings_alert_id, notification.build());
            requestData();
        }
    }

    public void addToWatchSet(DataMap dataMap) {

        ArrayList<DataMap> entries = dataMap.getDataMapArrayList("entries");
        if (entries != null) {
            for (DataMap entry : entries) {
                double sgv = entry.getDouble("sgvDouble");
                double high = entry.getDouble("high");
                double low = entry.getDouble("low");
                double timestamp = entry.getDouble("timestamp");

                final int size = bgDataList.size();
                if (size > 0) {
                    if (bgDataList.get(size - 1).timestamp == timestamp)
                        continue; // Ignore duplicates.
                }

                bgDataList.add(new BgWatchData(sgv, high, low, timestamp));
            }
        } else {
            double sgv = dataMap.getDouble("sgvDouble");
            double high = dataMap.getDouble("high");
            double low = dataMap.getDouble("low");
            double timestamp = dataMap.getDouble("timestamp");

            final int size = bgDataList.size();
            if (size > 0) {
                if (bgDataList.get(size - 1).timestamp == timestamp)
                    return; // Ignore duplicates.
            }

            bgDataList.add(new BgWatchData(sgv, high, low, timestamp));
        }

        for (int i = 0; i < bgDataList.size(); i++) {
            if (bgDataList.get(i).timestamp < (new Date().getTime() - (1000 * 60 * 60 * 5))) {
                bgDataList.remove(i); //Get rid of anything more than 5 hours old
                break;
            }
        }
    }

    public void setupCharts() {
        if(bgDataList.size() > 0) { //Dont crash things just because we dont have values, people dont like crashy things
            if (singleLine) {
                bgGraphBuilder = new BgGraphBuilder(getApplicationContext(), bgDataList, pointSize, midColor);
            } else {
                bgGraphBuilder = new BgGraphBuilder(getApplicationContext(), bgDataList, pointSize, highColor, lowColor, midColor);
            }

            chart.setLineChartData(bgGraphBuilder.lineData());
            chart.setViewportCalculationEnabled(true);
            chart.setMaximumViewport(chart.getMaximumViewport());
        }
        if (bgDataList.size() <= 2) {
            requestData();
        }
    }
}
