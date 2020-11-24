package com.android.systemui.doze;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.hardware.display.AmbientDisplayConfiguration;
import android.net.Uri;
import android.os.Debug;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.OpFeatures;
import com.android.systemui.R$string;
import com.android.systemui.SystemUIApplication;
import com.android.systemui.doze.DozeSensors;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.AlarmTimeout;
import com.android.systemui.util.AsyncSensorManager;
import com.android.systemui.util.wakelock.WakeLock;
import com.oneplus.aod.OpAodUtils;
import com.oneplus.sarah.SarahClient;
import java.io.PrintWriter;
import java.util.function.Consumer;

public class DozeSensors {
    private static final boolean DEBUG = DozeService.DEBUG;
    private final AlarmManager mAlarmManager;
    private final Callback mCallback;
    private final AmbientDisplayConfiguration mConfig;
    private final Context mContext;
    private CustomProximityCheck mCustomProximityCheck;
    private long mDebounceFrom;
    private final DozeParameters mDozeParameters;
    private final Handler mHandler = new Handler();
    private MotionCheck mMotionCheck;
    private PickupCheck mPickUpCheck;
    private final TriggerSensor mPickupSensor;
    private final Consumer<Boolean> mProxCallback;
    private final ProxSensor mProxSensor;
    private int mProximityResult = 0;
    private final ContentResolver mResolver;
    private final SensorManager mSensorManager;
    protected TriggerSensor[] mSensors;
    private boolean mSettingRegistered;
    private final ContentObserver mSettingsObserver = new ContentObserver(this.mHandler) {
        /* class com.android.systemui.doze.DozeSensors.AnonymousClass2 */

        public void onChange(boolean z, Uri uri, int i) {
            if (i == ActivityManager.getCurrentUser()) {
                for (TriggerSensor triggerSensor : DozeSensors.this.mSensors) {
                    triggerSensor.updateListener();
                }
            }
        }
    };
    private final WakeLock mWakeLock;

    public interface Callback {
        void onSensorPulse(int i, boolean z, float f, float f2, float[] fArr);
    }

    public DozeSensors(Context context, AlarmManager alarmManager, SensorManager sensorManager, DozeParameters dozeParameters, AmbientDisplayConfiguration ambientDisplayConfiguration, WakeLock wakeLock, Callback callback, Consumer<Boolean> consumer, AlwaysOnDisplayPolicy alwaysOnDisplayPolicy) {
        this.mContext = context;
        this.mAlarmManager = alarmManager;
        this.mSensorManager = sensorManager;
        this.mDozeParameters = dozeParameters;
        this.mConfig = ambientDisplayConfiguration;
        this.mWakeLock = wakeLock;
        this.mProxCallback = consumer;
        this.mResolver = this.mContext.getContentResolver();
        boolean alwaysOnEnabled = this.mConfig.alwaysOnEnabled(-2);
        TriggerSensor[] triggerSensorArr = new TriggerSensor[7];
        triggerSensorArr[0] = new TriggerSensor(this, this.mSensorManager.getDefaultSensor(17), null, dozeParameters.getPulseOnSigMotion(), 2, false, false);
        TriggerSensor triggerSensor = new TriggerSensor(this, this.mSensorManager.getDefaultSensor(25), "doze_pulse_on_pick_up", ambientDisplayConfiguration.dozePickupSensorAvailable(), 3, false, false);
        this.mPickupSensor = triggerSensor;
        triggerSensorArr[1] = triggerSensor;
        triggerSensorArr[2] = new TriggerSensor(this, findSensorWithType(ambientDisplayConfiguration.doubleTapSensorType()), "doze_pulse_on_double_tap", true, 4, dozeParameters.doubleTapReportsTouchCoordinates(), true);
        triggerSensorArr[3] = new TriggerSensor(this, findSensorWithType(ambientDisplayConfiguration.tapSensorType()), "doze_tap_gesture", true, 9, false, true);
        triggerSensorArr[4] = new TriggerSensor(this, findSensorWithType(ambientDisplayConfiguration.longPressSensorType()), "doze_pulse_on_long_press", false, true, 5, true, true);
        triggerSensorArr[5] = new PluginSensor(this, new SensorManagerPlugin.Sensor(2), "doze_wake_screen_gesture", this.mConfig.wakeScreenGestureAvailable() && alwaysOnEnabled, 7, false, false);
        triggerSensorArr[6] = new PluginSensor(new SensorManagerPlugin.Sensor(1), "doze_wake_screen_gesture", this.mConfig.wakeScreenGestureAvailable() && alwaysOnEnabled, 8, false, false, this.mConfig.getWakeLockScreenDebounce());
        this.mSensors = triggerSensorArr;
        boolean isPulsingBlocked = ((DozeHost) ((SystemUIApplication) this.mContext.getApplicationContext()).getComponent(DozeHost.class)).isPulsingBlocked();
        if (!OpFeatures.isSupport(new int[]{80}) || isPulsingBlocked) {
            this.mPickUpCheck = new PickupCheck();
        } else {
            this.mMotionCheck = new MotionCheck(true, 3);
            this.mCustomProximityCheck = new CustomProximityCheck() {
                /* class com.android.systemui.doze.DozeSensors.AnonymousClass1 */

                @Override // com.android.systemui.doze.DozeSensors.CustomProximityCheck
                public void onProximityResult(int i) {
                    Log.d("DozeSensors", "onProximityResult: " + i);
                    int i2 = DozeSensors.this.mProximityResult;
                    DozeSensors.this.mProximityResult = i;
                    if (i2 == 1 && i == 2 && DozeSensors.this.mMotionCheck.getCurrentState() == 1) {
                        DozeSensors.this.mMotionCheck.resetCurrentState();
                        Log.d("DozeSensors", "pulse from pocket");
                        DozeSensors.this.mCallback.onSensorPulse(3, false, -1.0f, -1.0f, null);
                    }
                }
            };
        }
        this.mProxSensor = new ProxSensor(alwaysOnDisplayPolicy);
        this.mCallback = callback;
    }

    public int getCustomProximityResult() {
        return this.mProximityResult;
    }

    public void requestTemporaryDisable() {
        this.mDebounceFrom = SystemClock.uptimeMillis();
    }

    private Sensor findSensorWithType(String str) {
        return findSensorWithType(this.mSensorManager, str);
    }

    static Sensor findSensorWithType(SensorManager sensorManager, String str) {
        if (TextUtils.isEmpty(str)) {
            return null;
        }
        for (Sensor sensor : sensorManager.getSensorList(-1)) {
            if (str.equals(sensor.getStringType())) {
                return sensor;
            }
        }
        return null;
    }

    public void setListening(boolean z) {
        if (OpAodUtils.isMotionAwakeOn()) {
            MotionCheck motionCheck = this.mMotionCheck;
            if (motionCheck != null) {
                motionCheck.setListening(z);
            }
            PickupCheck pickupCheck = this.mPickUpCheck;
            if (pickupCheck != null) {
                pickupCheck.setListening(z);
            }
        }
        for (TriggerSensor triggerSensor : this.mSensors) {
            triggerSensor.setListening(z);
        }
        registerSettingsObserverIfNeeded(z);
    }

    public void setTouchscreenSensorsListening(boolean z) {
        TriggerSensor[] triggerSensorArr = this.mSensors;
        for (TriggerSensor triggerSensor : triggerSensorArr) {
            if (triggerSensor.mRequiresTouchscreen) {
                triggerSensor.setListening(z);
            }
        }
    }

    public void onUserSwitched() {
        for (TriggerSensor triggerSensor : this.mSensors) {
            triggerSensor.updateListener();
        }
    }

    public void setCustomProxListening(boolean z) {
        CustomProximityCheck customProximityCheck;
        if (shouldListenProximity() && (customProximityCheck = this.mCustomProximityCheck) != null) {
            customProximityCheck.setListening(z);
        }
    }

    private boolean shouldListenProximity() {
        if (OpFeatures.isSupport(new int[]{114})) {
            if (OpFeatures.isSupport(new int[]{80})) {
                return false;
            }
        }
        return true;
    }

    public void setProxListening(boolean z) {
        if (shouldListenProximity()) {
            this.mProxSensor.setRequested(z);
        }
    }

    public void setDisableSensorsInterferingWithProximity(boolean z) {
        this.mPickupSensor.setDisabled(z);
    }

    public void dump(PrintWriter printWriter) {
        TriggerSensor[] triggerSensorArr = this.mSensors;
        for (TriggerSensor triggerSensor : triggerSensorArr) {
            printWriter.print("  Sensor: ");
            printWriter.println(triggerSensor.toString());
        }
        printWriter.print("  ProxSensor: ");
        printWriter.println(this.mProxSensor.toString());
    }

    public Boolean isProximityCurrentlyFar() {
        return this.mProxSensor.mCurrentlyFar;
    }

    public void resetMotionValue() {
        MotionCheck motionCheck = this.mMotionCheck;
        if (motionCheck != null) {
            motionCheck.resetCurrentState();
        }
    }

    /* access modifiers changed from: private */
    public class MotionCheck implements SensorEventListener, Runnable {
        private boolean mConfigured;
        private int mCurrentState;
        private boolean mFinished = false;
        private float mMaxRange;
        private boolean mProximityChecking;
        private int mPulseReason;
        private boolean mRegistered;
        private int mSensorType = 33171028;
        private final String mTag = "DozeSensor.MotionCheck";

        public void onAccuracyChanged(Sensor sensor, int i) {
        }

        public void run() {
        }

        public MotionCheck(boolean z, int i) {
            Log.d("DozeSensors", "choose sensor: " + "TYPE_MOTION");
            this.mConfigured = z;
            this.mPulseReason = i;
        }

        public void check() {
            if (!this.mFinished && !this.mRegistered && this.mConfigured) {
                Sensor defaultSensor = DozeSensors.this.mSensorManager.getDefaultSensor(this.mSensorType);
                if (defaultSensor == null) {
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensor.MotionCheck", "No sensor found");
                    }
                    finishWithResult(0);
                    return;
                }
                Log.d("DozeSensor.MotionCheck", "sensor registered " + hashCode());
                this.mMaxRange = defaultSensor.getMaximumRange();
                DozeSensors.this.mSensorManager.registerListener(this, defaultSensor, 3, 0, DozeSensors.this.mHandler);
                DozeSensors.this.mHandler.postDelayed(this, 500);
                this.mRegistered = true;
            }
        }

        public void onSensorChanged(SensorEvent sensorEvent) {
            Log.d("DozeSensor.MotionCheck", "onSensorChanged: proximity checking = " + this.mProximityChecking);
            if (!this.mProximityChecking) {
                float[] fArr = sensorEvent.values;
                if (fArr.length == 0) {
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensor.MotionCheck", "Event has no values!");
                    }
                    finishWithResult(0);
                } else if (fArr[0] == 1.0f) {
                    finishWithResult(1);
                } else if (((double) fArr[0]) == 2.0d) {
                    finishWithResult(2);
                } else if (fArr[0] == 0.0f) {
                    finishWithResult(3);
                    DozeSensors.this.mHandler.removeCallbacks(this);
                } else if (fArr[0] == -1.0f) {
                    finishWithResult(4);
                }
                Log.d("DozeSensor.MotionCheck", "onSensorChanged: value = " + sensorEvent.values[0]);
                if (sensorEvent.values[0] != 0.0f && DozeSensors.this.mContext != null) {
                    SarahClient.getInstance(DozeSensors.this.mContext).notifyAodOnReason(sensorEvent.values[1]);
                }
            }
        }

        private void finishWithResult(int i) {
            if (this.mRegistered) {
                if (this.mCurrentState != 0 && i == 1) {
                    DozeSensors.this.mCallback.onSensorPulse(this.mPulseReason, false, -1.0f, -1.0f, null);
                } else if (this.mCurrentState == 0 || i != 2) {
                    int i2 = this.mCurrentState;
                    if ((i2 == 2 || i2 == 1) && i == 4) {
                        DozeSensors.this.mCallback.onSensorPulse(-1, true, -1.0f, -1.0f, null);
                        i = 3;
                    }
                } else {
                    DozeSensors.this.mCallback.onSensorPulse(this.mPulseReason, true, -1.0f, -1.0f, null);
                }
                this.mCurrentState = i;
            }
        }

        public void setListening(boolean z) {
            if (z) {
                check();
            } else {
                release();
            }
        }

        public int getCurrentState() {
            return this.mCurrentState;
        }

        public void resetCurrentState() {
            this.mCurrentState = 3;
        }

        private void release() {
            if (this.mRegistered && DozeSensors.this.mSensorManager != null) {
                Log.d("DozeSensor.MotionCheck", "Unregister Motion Sensor " + hashCode());
                DozeSensors.this.mSensorManager.unregisterListener(this);
                this.mRegistered = false;
            }
        }
    }

    private class PickupCheck implements SensorEventListener, Runnable {
        private int mCurrentState;
        private boolean mFinished = false;
        private float mMaxRange;
        private boolean mProximityChecking;
        private boolean mRegistered;
        private int mSensorType = 33171026;
        private final String mTag = "DozeSensors.PickupCheck";

        public void onAccuracyChanged(Sensor sensor, int i) {
        }

        public void run() {
        }

        public PickupCheck() {
            Log.d("DozeSensors", "choose sensor: " + "TYPE_PICK_UP");
        }

        public void check() {
            if (!this.mFinished && !this.mRegistered) {
                Sensor defaultSensor = DozeSensors.this.mSensorManager.getDefaultSensor(this.mSensorType);
                if (defaultSensor == null) {
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors.PickupCheck", "No sensor found");
                    }
                    finishWithResult(0);
                    return;
                }
                Log.d("DozeSensors.PickupCheck", "sensor registered");
                this.mMaxRange = defaultSensor.getMaximumRange();
                DozeSensors.this.mSensorManager.registerListener(this, defaultSensor, 3, 0, DozeSensors.this.mHandler);
                DozeSensors.this.mHandler.postDelayed(this, 500);
                this.mRegistered = true;
            }
        }

        public void onSensorChanged(SensorEvent sensorEvent) {
            Log.i("DozeSensors", "onSensorChanged = " + sensorEvent);
            if (!this.mProximityChecking) {
                boolean z = false;
                if (sensorEvent.values.length == 0) {
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors.PickupCheck", "Event has no values!");
                    }
                    finishWithResult(0);
                    return;
                }
                if (DozeSensors.DEBUG) {
                    Log.d("DozeSensors.PickupCheck", "Event: value=" + sensorEvent.values[0] + " max=" + this.mMaxRange);
                }
                int i = 1;
                if (sensorEvent.values[0] == 1.0f) {
                    z = true;
                }
                if (!z) {
                    i = 2;
                }
                finishWithResult(i);
            }
        }

        private void finishWithResult(int i) {
            if (this.mRegistered) {
                if (!OpAodUtils.isAlwaysOnEnabled()) {
                    if (this.mCurrentState != 0 && i == 1) {
                        DozeSensors.this.mCallback.onSensorPulse(3, false, -1.0f, -1.0f, null);
                    } else if (i == 2) {
                        DozeSensors.this.mCallback.onSensorPulse(-1, false, -1.0f, -1.0f, null);
                    }
                }
                this.mCurrentState = i;
            }
        }

        public void setListening(boolean z) {
            if (z) {
                check();
            } else {
                release();
            }
        }

        private void release() {
            if (this.mRegistered && DozeSensors.this.mSensorManager != null) {
                Log.d("DozeSensors.PickupCheck", "Unregister P Sensor");
                DozeSensors.this.mSensorManager.unregisterListener(this);
                this.mRegistered = false;
            }
        }
    }

    private abstract class CustomProximityCheck implements SensorEventListener, Runnable {
        private boolean mFinished;
        private float mMaxRange;
        private boolean mRegistered;
        private final String mTag;

        public void onAccuracyChanged(Sensor sensor, int i) {
        }

        public abstract void onProximityResult(int i);

        private CustomProximityCheck() {
            this.mTag = "DozeSensor.CustomProximityCheck";
        }

        public void check() {
            if (!this.mFinished && !this.mRegistered) {
                Sensor defaultSensor = DozeSensors.this.mSensorManager.getDefaultSensor(33171025);
                if (defaultSensor == null) {
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensor.CustomProximityCheck", "No sensor found");
                    }
                    finishWithResult(0);
                    return;
                }
                Log.d("DozeSensor.CustomProximityCheck", "register pocket " + hashCode());
                this.mMaxRange = defaultSensor.getMaximumRange();
                DozeSensors.this.mSensorManager.registerListener(this, defaultSensor, 3, 0, DozeSensors.this.mHandler);
                DozeSensors.this.mHandler.postDelayed(this, 500);
                this.mRegistered = true;
            }
        }

        public void setListening(boolean z) {
            if (z) {
                check();
            } else {
                release();
            }
        }

        public void onSensorChanged(SensorEvent sensorEvent) {
            boolean z = false;
            if (sensorEvent.values.length == 0) {
                if (DozeSensors.DEBUG) {
                    Log.d("DozeSensor.CustomProximityCheck", "Event has no values!");
                }
                finishWithResult(0);
                return;
            }
            if (DozeSensors.DEBUG) {
                Log.d("DozeSensor.CustomProximityCheck", "Event: value=" + sensorEvent.values[0] + " max=" + this.mMaxRange);
            }
            int i = 1;
            if (sensorEvent.values[0] == 1.0f) {
                z = true;
            }
            if (!z) {
                i = 2;
            }
            finishWithResult(i);
        }

        public void run() {
            if (DozeSensors.DEBUG) {
                Log.d("DozeSensor.CustomProximityCheck", "No event received before timeout");
            }
            finishWithResult(0);
        }

        private void finishWithResult(int i) {
            if (this.mRegistered) {
                DozeSensors.this.mHandler.removeCallbacks(this);
            }
            onProximityResult(i);
        }

        private void release() {
            if (this.mRegistered && DozeSensors.this.mSensorManager != null) {
                Log.d("DozeSensor.CustomProximityCheck", "Unregister pocket Sensor " + hashCode());
                DozeSensors.this.mSensorManager.unregisterListener(this);
                this.mRegistered = false;
            }
        }
    }

    private void registerSettingsObserverIfNeeded(boolean z) {
        if (!z) {
            this.mResolver.unregisterContentObserver(this.mSettingsObserver);
        } else if (!this.mSettingRegistered) {
            for (TriggerSensor triggerSensor : this.mSensors) {
                triggerSensor.registerSettingsObserver(this.mSettingsObserver);
            }
        }
        this.mSettingRegistered = z;
    }

    /* access modifiers changed from: private */
    public class ProxSensor implements SensorEventListener {
        final AlarmTimeout mCooldownTimer;
        Boolean mCurrentlyFar;
        long mLastNear;
        final AlwaysOnDisplayPolicy mPolicy;
        boolean mRegistered;
        boolean mRequested;
        final Sensor mSensor;

        public void onAccuracyChanged(Sensor sensor, int i) {
        }

        public ProxSensor(AlwaysOnDisplayPolicy alwaysOnDisplayPolicy) {
            this.mPolicy = alwaysOnDisplayPolicy;
            this.mCooldownTimer = new AlarmTimeout(DozeSensors.this.mAlarmManager, new AlarmManager.OnAlarmListener() {
                /* class com.android.systemui.doze.$$Lambda$DozeSensors$ProxSensor$1rrJyrKR8bANwbetqs61eKIcvs */

                public final void onAlarm() {
                    DozeSensors.ProxSensor.m7lambda$1rrJyrKR8bANwbetqs61eKIcvs(DozeSensors.ProxSensor.this);
                }
            }, "prox_cooldown", DozeSensors.this.mHandler);
            Sensor findSensorWithType = DozeSensors.findSensorWithType(DozeSensors.this.mSensorManager, DozeSensors.this.mContext.getString(R$string.doze_brightness_sensor_type));
            this.mSensor = findSensorWithType == null ? DozeSensors.this.mSensorManager.getDefaultSensor(8) : findSensorWithType;
        }

        /* access modifiers changed from: package-private */
        public void setRequested(boolean z) {
            if (this.mRequested == z) {
                DozeSensors.this.mHandler.post(new Runnable() {
                    /* class com.android.systemui.doze.$$Lambda$DozeSensors$ProxSensor$ocSoA7n0sI8mkM1nacSopw2_2Oc */

                    public final void run() {
                        DozeSensors.ProxSensor.this.lambda$setRequested$0$DozeSensors$ProxSensor();
                    }
                });
                return;
            }
            this.mRequested = z;
            updateRegistered();
        }

        public /* synthetic */ void lambda$setRequested$0$DozeSensors$ProxSensor() {
            if (this.mCurrentlyFar != null) {
                DozeSensors.this.mProxCallback.accept(this.mCurrentlyFar);
            }
        }

        /* access modifiers changed from: private */
        public void updateRegistered() {
            setRegistered(this.mRequested && !this.mCooldownTimer.isScheduled());
        }

        private void setRegistered(boolean z) {
            if (this.mRegistered != z) {
                if (DozeSensors.DEBUG) {
                    Log.d("DozeSensors", "setRegistered: " + z + ", " + Debug.getCallers(5));
                }
                if (z) {
                    this.mRegistered = DozeSensors.this.mSensorManager.registerListener(this, DozeSensors.this.mSensorManager.getDefaultSensor(33171025), 3, DozeSensors.this.mHandler);
                    return;
                }
                DozeSensors.this.mSensorManager.unregisterListener(this);
                this.mRegistered = false;
                this.mCurrentlyFar = null;
            }
        }

        public void onSensorChanged(SensorEvent sensorEvent) {
            if (DozeSensors.DEBUG) {
                Log.d("DozeSensors", "onSensorChanged " + sensorEvent);
            }
            boolean z = false;
            if (sensorEvent.values[0] != 1.0f) {
                z = true;
            }
            this.mCurrentlyFar = Boolean.valueOf(z);
            DozeSensors.this.mProxCallback.accept(this.mCurrentlyFar);
            long elapsedRealtime = SystemClock.elapsedRealtime();
            Boolean bool = this.mCurrentlyFar;
            if (bool != null) {
                if (!bool.booleanValue()) {
                    this.mLastNear = elapsedRealtime;
                } else if (this.mCurrentlyFar.booleanValue()) {
                    AlwaysOnDisplayPolicy alwaysOnDisplayPolicy = this.mPolicy;
                    if (elapsedRealtime - this.mLastNear < alwaysOnDisplayPolicy.proxCooldownTriggerMs) {
                        this.mCooldownTimer.schedule(alwaysOnDisplayPolicy.proxCooldownPeriodMs, 1);
                        updateRegistered();
                    }
                }
            }
        }

        public String toString() {
            return String.format("{registered=%s, requested=%s, coolingDown=%s, currentlyFar=%s, sensor=%s}", Boolean.valueOf(this.mRegistered), Boolean.valueOf(this.mRequested), Boolean.valueOf(this.mCooldownTimer.isScheduled()), this.mCurrentlyFar, this.mSensor);
        }
    }

    /* access modifiers changed from: package-private */
    public class TriggerSensor extends TriggerEventListener {
        final boolean mConfigured;
        protected boolean mDisabled;
        protected boolean mIgnoresSetting;
        final int mPulseReason;
        protected boolean mRegistered;
        final boolean mReportsTouchCoordinates;
        protected boolean mRequested;
        final boolean mRequiresTouchscreen;
        final Sensor mSensor;
        final String mSetting;
        final boolean mSettingDefault;

        public TriggerSensor(DozeSensors dozeSensors, Sensor sensor, String str, boolean z, int i, boolean z2, boolean z3) {
            this(dozeSensors, sensor, str, true, z, i, z2, z3);
        }

        public TriggerSensor(DozeSensors dozeSensors, Sensor sensor, String str, boolean z, boolean z2, int i, boolean z3, boolean z4) {
            this(sensor, str, z, z2, i, z3, z4, false);
        }

        private TriggerSensor(Sensor sensor, String str, boolean z, boolean z2, int i, boolean z3, boolean z4, boolean z5) {
            this.mSensor = sensor;
            this.mSetting = str;
            this.mSettingDefault = z;
            this.mConfigured = z2;
            this.mPulseReason = i;
            this.mReportsTouchCoordinates = z3;
            this.mRequiresTouchscreen = z4;
            this.mIgnoresSetting = z5;
        }

        public void setListening(boolean z) {
            if (this.mRequested != z) {
                this.mRequested = z;
                updateListener();
            }
        }

        public void setDisabled(boolean z) {
            if (this.mDisabled != z) {
                this.mDisabled = z;
                updateListener();
            }
        }

        public void updateListener() {
            if (this.mConfigured && this.mSensor != null) {
                if (this.mRequested && !this.mDisabled && ((enabledBySetting() || this.mIgnoresSetting) && !this.mRegistered)) {
                    this.mRegistered = DozeSensors.this.mSensorManager.requestTriggerSensor(this, this.mSensor);
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors", "requestTriggerSensor " + this.mRegistered);
                    }
                } else if (this.mRegistered) {
                    boolean cancelTriggerSensor = DozeSensors.this.mSensorManager.cancelTriggerSensor(this, this.mSensor);
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors", "cancelTriggerSensor " + cancelTriggerSensor);
                    }
                    this.mRegistered = false;
                }
            }
        }

        /* access modifiers changed from: protected */
        public boolean enabledBySetting() {
            if (!DozeSensors.this.mConfig.enabled(-2)) {
                return false;
            }
            if (TextUtils.isEmpty(this.mSetting)) {
                return true;
            }
            if (Settings.Secure.getIntForUser(DozeSensors.this.mResolver, this.mSetting, this.mSettingDefault ? 1 : 0, -2) != 0) {
                return true;
            }
            return false;
        }

        public String toString() {
            return "{mRegistered=" + this.mRegistered + ", mRequested=" + this.mRequested + ", mDisabled=" + this.mDisabled + ", mConfigured=" + this.mConfigured + ", mIgnoresSetting=" + this.mIgnoresSetting + ", mSensor=" + this.mSensor + "}";
        }

        public void onTrigger(TriggerEvent triggerEvent) {
            DozeLog.traceSensor(DozeSensors.this.mContext, this.mPulseReason);
            DozeSensors.this.mHandler.post(DozeSensors.this.mWakeLock.wrap(new Runnable(triggerEvent) {
                /* class com.android.systemui.doze.$$Lambda$DozeSensors$TriggerSensor$O2XJN2HKJ96bSF_1qNx6jPKeFk */
                private final /* synthetic */ TriggerEvent f$1;

                {
                    this.f$1 = r2;
                }

                public final void run() {
                    DozeSensors.TriggerSensor.this.lambda$onTrigger$0$DozeSensors$TriggerSensor(this.f$1);
                }
            }));
        }

        /* JADX WARNING: Removed duplicated region for block: B:17:0x0073  */
        /* JADX WARNING: Removed duplicated region for block: B:19:? A[RETURN, SYNTHETIC] */
        /* Code decompiled incorrectly, please refer to instructions dump. */
        public /* synthetic */ void lambda$onTrigger$0$DozeSensors$TriggerSensor(android.hardware.TriggerEvent r9) {
            /*
            // Method dump skipped, instructions count: 119
            */
            throw new UnsupportedOperationException("Method not decompiled: com.android.systemui.doze.DozeSensors.TriggerSensor.lambda$onTrigger$0$DozeSensors$TriggerSensor(android.hardware.TriggerEvent):void");
        }

        public void registerSettingsObserver(ContentObserver contentObserver) {
            if (this.mConfigured && !TextUtils.isEmpty(this.mSetting)) {
                DozeSensors.this.mResolver.registerContentObserver(Settings.Secure.getUriFor(this.mSetting), false, DozeSensors.this.mSettingsObserver, -1);
            }
        }

        /* access modifiers changed from: protected */
        public String triggerEventToString(TriggerEvent triggerEvent) {
            if (triggerEvent == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("SensorEvent[");
            sb.append(triggerEvent.timestamp);
            sb.append(',');
            sb.append(triggerEvent.sensor.getName());
            if (triggerEvent.values != null) {
                for (int i = 0; i < triggerEvent.values.length; i++) {
                    sb.append(',');
                    sb.append(triggerEvent.values[i]);
                }
            }
            sb.append(']');
            return sb.toString();
        }
    }

    /* access modifiers changed from: package-private */
    public class PluginSensor extends TriggerSensor implements SensorManagerPlugin.SensorEventListener {
        private long mDebounce;
        final SensorManagerPlugin.Sensor mPluginSensor;

        PluginSensor(DozeSensors dozeSensors, SensorManagerPlugin.Sensor sensor, String str, boolean z, int i, boolean z2, boolean z3) {
            this(sensor, str, z, i, z2, z3, 0);
        }

        PluginSensor(SensorManagerPlugin.Sensor sensor, String str, boolean z, int i, boolean z2, boolean z3, long j) {
            super(DozeSensors.this, null, str, z, i, z2, z3);
            this.mPluginSensor = sensor;
            this.mDebounce = j;
        }

        @Override // com.android.systemui.doze.DozeSensors.TriggerSensor
        public void updateListener() {
            if (this.mConfigured) {
                AsyncSensorManager asyncSensorManager = (AsyncSensorManager) DozeSensors.this.mSensorManager;
                if (this.mRequested && !this.mDisabled && ((enabledBySetting() || this.mIgnoresSetting) && !this.mRegistered)) {
                    asyncSensorManager.registerPluginListener(this.mPluginSensor, this);
                    this.mRegistered = true;
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors", "registerPluginListener");
                    }
                } else if (this.mRegistered) {
                    asyncSensorManager.unregisterPluginListener(this.mPluginSensor, this);
                    this.mRegistered = false;
                    if (DozeSensors.DEBUG) {
                        Log.d("DozeSensors", "unregisterPluginListener");
                    }
                }
            }
        }

        @Override // com.android.systemui.doze.DozeSensors.TriggerSensor
        public String toString() {
            return "{mRegistered=" + this.mRegistered + ", mRequested=" + this.mRequested + ", mDisabled=" + this.mDisabled + ", mConfigured=" + this.mConfigured + ", mIgnoresSetting=" + this.mIgnoresSetting + ", mSensor=" + this.mPluginSensor + "}";
        }

        private String triggerEventToString(SensorManagerPlugin.SensorEvent sensorEvent) {
            if (sensorEvent == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("PluginTriggerEvent[");
            sb.append(sensorEvent.getSensor());
            sb.append(',');
            sb.append(sensorEvent.getVendorType());
            if (sensorEvent.getValues() != null) {
                for (int i = 0; i < sensorEvent.getValues().length; i++) {
                    sb.append(',');
                    sb.append(sensorEvent.getValues()[i]);
                }
            }
            sb.append(']');
            return sb.toString();
        }

        @Override // com.android.systemui.plugins.SensorManagerPlugin.SensorEventListener
        public void onSensorChanged(SensorManagerPlugin.SensorEvent sensorEvent) {
            DozeLog.traceSensor(DozeSensors.this.mContext, this.mPulseReason);
            DozeSensors.this.mHandler.post(DozeSensors.this.mWakeLock.wrap(new Runnable(sensorEvent) {
                /* class com.android.systemui.doze.$$Lambda$DozeSensors$PluginSensor$EFDqlQhDL6RwEmmtbTd8M88V_8Y */
                private final /* synthetic */ SensorManagerPlugin.SensorEvent f$1;

                {
                    this.f$1 = r2;
                }

                public final void run() {
                    DozeSensors.PluginSensor.this.lambda$onSensorChanged$0$DozeSensors$PluginSensor(this.f$1);
                }
            }));
        }

        public /* synthetic */ void lambda$onSensorChanged$0$DozeSensors$PluginSensor(SensorManagerPlugin.SensorEvent sensorEvent) {
            if (SystemClock.uptimeMillis() >= DozeSensors.this.mDebounceFrom + this.mDebounce) {
                if (DozeSensors.DEBUG) {
                    Log.d("DozeSensors", "onSensorEvent: " + triggerEventToString(sensorEvent));
                }
                DozeSensors.this.mCallback.onSensorPulse(this.mPulseReason, true, -1.0f, -1.0f, sensorEvent.getValues());
            } else if (DozeSensors.DEBUG) {
                Log.d("DozeSensors", "onSensorEvent dropped: " + triggerEventToString(sensorEvent));
            }
        }
    }
}