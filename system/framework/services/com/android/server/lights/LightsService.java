package com.android.server.lights;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Parcel;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.os.Trace;
import android.provider.Settings;
import android.util.OpFeatures;
import android.util.Slog;
import android.view.SurfaceControl;
import com.android.server.SystemService;
import com.oneplus.android.context.IOneplusContext;
import com.oneplus.android.context.OneplusContext;
import com.oneplus.core.oimc.OIMCFunction;
import com.oneplus.core.oimc.OIMCServiceManager;
import com.oneplus.display.IOneplusColorDisplayManager;

public class LightsService extends SystemService {
    public static boolean DEBUG = false;
    private static final boolean FEATURE_EXTREME_ENABLE = OpFeatures.isSupport(new int[]{320});
    static final String TAG = "LightsService";
    private static OIMCServiceManager mOIMCService;
    IBinder flinger = null;
    private final Context mContext;
    private Handler mH = new Handler() {
        /* class com.android.server.lights.LightsService.AnonymousClass2 */

        public void handleMessage(Message msg) {
            ((LightImpl) msg.obj).stopFlashing();
        }
    };
    final LightImpl[] mLights = new LightImpl[8];
    private IOneplusColorDisplayManager mOPColorDisplayManager;
    private final LightsManager mService = new LightsManager() {
        /* class com.android.server.lights.LightsService.AnonymousClass1 */

        @Override // com.android.server.lights.LightsManager
        public Light getLight(int id) {
            if (id < 0 || id >= 8) {
                return null;
            }
            return LightsService.this.mLights[id];
        }
    };

    static native void setLight_native(int i, int i2, int i3, int i4, int i5, int i6);

    private final class LightImpl extends Light {
        private int mBrightnessMode;
        private int mColor;
        private final IBinder mDisplayToken;
        private boolean mFlashing;
        private int mId;
        private boolean mInitialized;
        private int mLastBrightnessMode;
        private int mLastColor;
        private int mMode;
        private int mOffMS;
        private int mOnMS;
        private final int mSurfaceControlMaximumBrightness;
        private boolean mUseLowPersistenceForVR;
        private boolean mVrModeEnabled;

        private LightImpl(Context context, int id) {
            PowerManager pm;
            this.mId = id;
            this.mDisplayToken = SurfaceControl.getInternalDisplayToken();
            boolean brightnessSupport = SurfaceControl.getDisplayBrightnessSupport(this.mDisplayToken);
            if (LightsService.DEBUG) {
                Slog.d(LightsService.TAG, "Display brightness support: " + brightnessSupport);
            }
            int maximumBrightness = 0;
            if (brightnessSupport && (pm = (PowerManager) context.getSystemService(PowerManager.class)) != null) {
                maximumBrightness = pm.getMaximumScreenBrightnessSetting();
            }
            this.mSurfaceControlMaximumBrightness = maximumBrightness;
            LightsService.this.flinger = ServiceManager.getService("SurfaceFlinger");
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness) {
            setBrightness(brightness, 0);
        }

        @Override // com.android.server.lights.Light
        public void setBrightness(int brightness, int brightnessMode) {
            synchronized (this) {
                if (brightnessMode == 2) {
                    try {
                        Slog.w(LightsService.TAG, "setBrightness with LOW_PERSISTENCE unexpected #" + this.mId + ": brightness=0x" + Integer.toHexString(brightness));
                    } catch (Throwable th) {
                        throw th;
                    }
                } else {
                    if (brightnessMode == 0 && !shouldBeInLowPersistenceMode() && this.mSurfaceControlMaximumBrightness == 255) {
                        if (LightsService.DEBUG) {
                            Slog.d(LightsService.TAG, "Using new setBrightness path!");
                        }
                        SurfaceControl.setDisplayBrightness(this.mDisplayToken, ((float) brightness) / ((float) this.mSurfaceControlMaximumBrightness));
                    } else {
                        if (!OpFeatures.isSupport(new int[]{112}) || this.mId != 0) {
                            int color = brightness & 255;
                            setLightLocked(color | -16777216 | (color << 16) | (color << 8), 0, 0, 0, brightnessMode);
                        } else {
                            setLightLocked(brightness, 0, 0, 0, brightnessMode);
                        }
                    }
                    if (LightsService.this.mOPColorDisplayManager != null) {
                        LightsService.this.mOPColorDisplayManager.adjustLightColorGamut(brightness);
                    }
                }
            }
        }

        @Override // com.android.server.lights.Light
        public void setColor(int color) {
            synchronized (this) {
                setLightLocked(color, 0, 0, 0, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void setFlashing(int color, int mode, int onMS, int offMS) {
            synchronized (this) {
                setLightLocked(color, mode, onMS, offMS, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void pulse() {
            pulse(16777215, 7);
        }

        @Override // com.android.server.lights.Light
        public void pulse(int color, int onMS) {
            synchronized (this) {
                if (this.mColor == 0 && !this.mFlashing) {
                    setLightLocked(color, 2, onMS, 1000, 0);
                    this.mColor = 0;
                    LightsService.this.mH.sendMessageDelayed(Message.obtain(LightsService.this.mH, 1, this), (long) onMS);
                }
            }
        }

        @Override // com.android.server.lights.Light
        public void turnOff() {
            synchronized (this) {
                setLightLocked(0, 0, 0, 0, 0);
            }
        }

        @Override // com.android.server.lights.Light
        public void setVrMode(boolean enabled) {
            synchronized (this) {
                if (this.mVrModeEnabled != enabled) {
                    this.mVrModeEnabled = enabled;
                    this.mUseLowPersistenceForVR = LightsService.this.getVrDisplayMode() == 0;
                    if (shouldBeInLowPersistenceMode()) {
                        this.mLastBrightnessMode = this.mBrightnessMode;
                    }
                }
            }
        }

        /* access modifiers changed from: private */
        /* access modifiers changed from: public */
        private void stopFlashing() {
            synchronized (this) {
                setLightLocked(this.mColor, 0, 0, 0, 0);
            }
        }

        private void notifySurfaceFlingerLight(int color) {
            if (LightsService.FEATURE_EXTREME_ENABLE) {
                try {
                    if (LightsService.this.flinger != null) {
                        Parcel data = Parcel.obtain();
                        data.writeInterfaceToken("android.ui.ISurfaceComposer");
                        data.writeInt(color);
                        LightsService.this.flinger.transact(20013, data, null, 0);
                        data.recycle();
                        return;
                    }
                    Slog.i(LightsService.TAG, "flinger is null");
                } catch (RemoteException e) {
                    Slog.e(LightsService.TAG, "read flinger 20013 is fail");
                }
            }
        }

        private void setLightLocked(int color, int mode, int onMS, int offMS, int brightnessMode) {
            if (shouldBeInLowPersistenceMode()) {
                brightnessMode = 2;
            } else if (brightnessMode == 2) {
                brightnessMode = this.mLastBrightnessMode;
            }
            if (!(this.mInitialized && color == this.mColor && mode == this.mMode && onMS == this.mOnMS && offMS == this.mOffMS && this.mBrightnessMode == brightnessMode)) {
                if (LightsService.DEBUG) {
                    Slog.v(LightsService.TAG, "setLight #" + this.mId + ": color=#" + Integer.toHexString(color) + ": brightnessMode=" + brightnessMode);
                }
                this.mInitialized = true;
                this.mLastColor = this.mColor;
                this.mColor = color;
                this.mMode = mode;
                this.mOnMS = onMS;
                this.mOffMS = offMS;
                this.mBrightnessMode = brightnessMode;
                Trace.traceBegin(131072, "setLight(" + this.mId + ", 0x" + Integer.toHexString(color) + ")");
                if (this.mId == 0) {
                    notifySurfaceFlingerLight(color);
                }
                try {
                    LightsService.setLight_native(this.mId, color, mode, onMS, offMS, brightnessMode);
                } finally {
                    Trace.traceEnd(131072);
                }
            }
            if ((getDcEnable() && color < 260 && color > 1) || isFodEnabled()) {
                try {
                    if (LightsService.this.flinger != null) {
                        Parcel data = Parcel.obtain();
                        data.writeInterfaceToken("android.ui.ISurfaceComposer");
                        LightsService.this.flinger.transact(1004, data, null, 0);
                        data.recycle();
                        return;
                    }
                    Slog.i(LightsService.TAG, "flinger is null");
                } catch (RemoteException e) {
                    Slog.e(LightsService.TAG, "read flinger 1004 is fail");
                }
            }
        }

        private boolean shouldBeInLowPersistenceMode() {
            return this.mVrModeEnabled && this.mUseLowPersistenceForVR;
        }

        private boolean getDcEnable() {
            return SystemProperties.getInt("persist.vendor.dc.enable", 0) == 1;
        }

        private boolean isFodEnabled() {
            try {
                return LightsService.mOIMCService.getRemoteFuncStatus(OIMCFunction.COLORDISABLE) == 1;
            } catch (Exception e) {
                Slog.e(LightsService.TAG, "Failed to fetch FOD status: " + e.toString());
                return false;
            }
        }
    }

    public LightsService(Context context) {
        super(context);
        this.mContext = context;
        for (int i = 0; i < 8; i++) {
            this.mLights[i] = new LightImpl(context, i);
        }
    }

    @Override // com.android.server.SystemService
    public void onStart() {
        publishLocalService(LightsManager.class, this.mService);
    }

    @Override // com.android.server.SystemService
    public void onBootPhase(int phase) {
        if (phase == 1000) {
            this.mOPColorDisplayManager = (IOneplusColorDisplayManager) OneplusContext.queryInterface(IOneplusContext.EType.ONEPLUS_COLORDISPLAY_MANAGER);
            if (this.mOPColorDisplayManager == null) {
                Slog.e(TAG, "can not get color_display service!");
            }
        }
        if (phase == 500) {
            mOIMCService = new OIMCServiceManager();
        }
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private int getVrDisplayMode() {
        return Settings.Secure.getIntForUser(getContext().getContentResolver(), "vr_display_mode", 0, ActivityManager.getCurrentUser());
    }
}