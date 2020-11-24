package com.android.server.policy;

import android.app.ActivityManager;
import android.app.AppGlobals;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.LauncherApps;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.AudioAttributes;
import android.media.session.MediaSessionLegacyHelper;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.telecom.TelecomManager;
import android.util.Log;
import android.util.OpFeatures;
import android.view.KeyEvent;
import com.android.server.am.ActivityManagerServiceInjector;
import com.android.server.policy.keyguard.KeyguardServiceDelegate;
import com.oneplus.util.VibratorSceneUtils;
import java.io.File;
import java.util.Arrays;
import java.util.HashMap;

public class kth {
    private static final boolean DEBUG = SystemProperties.getBoolean("persist.sys.assert.panic", false);
    private static final String GK = "1";
    private static final String HK = "7";
    private static final String JK = "5";
    private static final String KK = "4";
    private static final int LK = 3000;
    private static final String MK = "15";
    private static final String NK = "6";
    private static final String PK = "2";
    private static final String QK = "14";
    private static final String RK = "12";
    private static final String SK = "13";
    private static final String TAG = "DeviceKeyHandler";
    private static final String TK = "OpenCamera";
    private static final AudioAttributes VIBRATION_ATTRIBUTES = new AudioAttributes.Builder().setContentType(4).setUsage(13).build();
    private static final String VK = "OpenTorch";
    private static final String WK = "FrontCamera";
    private static final String XK = "TakeVideo";
    private static final String YK = "OpenShelf";
    private static final String ZK = "OpenApp";
    private static final String _K = "OpenShortcut";
    private static final String aL = "/proc/touchpanel/gesture_enable";
    private static final String bL = "/proc/touchpanel/coordinate";
    private static final String cL = "/proc/touchpanel/gesture_switch";
    private static final long dL = 75;
    private static final long eL = 150;
    private static final String fL = "0";
    private static final float gL = 0.1f;
    private static final int hL = 1000;
    private boolean AK;
    private boolean BK = false;
    SensorEventListener DK = new rtg(this);
    SensorEventListener EK = new ssp(this);
    private final CameraManager.TorchCallback FK = new cno(this);
    final HashMap<String, zta> eK = new HashMap<>();
    PowerManager.WakeLock fK;
    PowerManager.WakeLock gK;
    PowerManager.WakeLock hK;
    private int iK = 0;
    private boolean jK = false;
    private boolean kK = true;
    private boolean lK = true;
    private ActivityManager mActivityManager;
    private final Context mContext;
    private you mEventHandler;
    private Handler mHandler;
    private HandlerThread mHandlerThread;
    private boolean mK = true;
    KeyguardServiceDelegate mKeyguardDelegate;
    private final Object mObject = new Object();
    private sis mObserver;
    private final PackageManager mPackageManager;
    private final PowerManager mPowerManager;
    private Sensor mProximitySensor;
    private boolean mProximitySensorEnabled = false;
    private boolean mSensorEnabled = false;
    private SensorManager mSensorManager;
    private boolean mSleeping = false;
    private boolean mSystemReady;
    private TelecomManager mTelecomManager;
    private Vibrator mVibrator;
    private boolean nK = true;
    private boolean oK = true;
    private boolean pK = true;
    private boolean qK = true;
    private boolean rK = true;
    LauncherApps sK;
    private boolean tK;
    private CameraManager uK;
    private boolean vK = true;
    private boolean wK = false;
    private zta xK;
    private boolean yK = false;
    private boolean zK = true;

    class sis extends ContentObserver {
        sis(Handler handler) {
            super(handler);
        }

        /* access modifiers changed from: package-private */
        public void observe() {
            ContentResolver contentResolver = kth.this.mContext.getContentResolver();
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gestrue_enable"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gesture_o"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gesture_v"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gesture_s"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gesture_w"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_blackscreen_gesture_m"), false, this, -1);
            contentResolver.registerContentObserver(Settings.System.getUriFor("oem_acc_anti_misoperation_screen"), false, this, -1);
            kth.this.Ep();
        }

        public void onChange(boolean z) {
            kth.this.Ep();
        }

        public void onChange(boolean z, Uri uri, int i) {
            super.onChange(z, uri, i);
        }
    }

    /* access modifiers changed from: private */
    public class you extends Handler {
        static final int xa = 1;

        private you() {
        }

        /* synthetic */ you(kth kth, you you2) {
            this();
        }

        public void handleMessage(Message message) {
            if (message.what == 1) {
                kth.this.Cp();
            }
        }
    }

    /* access modifiers changed from: private */
    public class zta {
        String dK;
        String mPackageName;
        String mShortcutId;
        int mUid;

        private zta() {
        }

        /* synthetic */ zta(kth kth, you you2) {
            this();
        }

        public void R(String str) {
            this.dK = str;
        }

        public void S(String str) {
            try {
                this.mUid = Integer.parseInt(str);
            } catch (NumberFormatException unused) {
                this.mUid = 0;
            }
        }

        public String getAction() {
            return this.dK;
        }

        public String getPackage() {
            return this.mPackageName;
        }

        public String getShortcutId() {
            return this.mShortcutId;
        }

        public int getUid() {
            return this.mUid;
        }

        public void setPackage(String str) {
            this.mPackageName = str;
        }

        public void setShortcutId(String str) {
            this.mShortcutId = str;
        }

        public String toString() {
            return "Name:" + this.dK + " Package:" + this.mPackageName + " ShortcutId:" + this.mShortcutId + " uid:" + this.mUid;
        }
    }

    public kth(Context context) {
        this.mContext = context;
        this.mPowerManager = (PowerManager) context.getSystemService("power");
        this.mEventHandler = new you(this, null);
        this.mSensorManager = (SensorManager) context.getSystemService("sensor");
        if (!OpFeatures.isSupport(new int[]{145})) {
            if (OpFeatures.isSupport(new int[]{232})) {
                this.BK = true;
            }
        }
        this.mProximitySensor = this.mSensorManager.getDefaultSensor(33171025);
        this.mVibrator = (Vibrator) context.getSystemService("vibrator");
        this.mActivityManager = (ActivityManager) context.getSystemService("activity");
        this.mPackageManager = context.getPackageManager();
        this.fK = this.mPowerManager.newWakeLock(1, "ProximityWakeLock");
        this.gK = this.mPowerManager.newWakeLock(1, "PartialGestureWakeLock");
        this.hK = this.mPowerManager.newWakeLock(268435457, "AcquireCauseWakeUpGestureWakeLock");
        this.mObserver = new sis(this.mEventHandler);
        this.uK = (CameraManager) context.getSystemService("camera");
        this.mHandlerThread = new HandlerThread(TAG, 10);
        this.mHandlerThread.start();
        this.mHandler = new Handler(this.mHandlerThread.getLooper());
        vd();
        this.wK = new File(cL).exists();
        this.mTelecomManager = (TelecomManager) this.mContext.getSystemService("telecom");
    }

    private void Ap() {
        Ia(true);
    }

    private void Bp() {
        Ja(true);
    }

    /* access modifiers changed from: private */
    /* JADX INFO: Can't fix incorrect switch cases order, some code will duplicate */
    /* access modifiers changed from: public */
    /* JADX WARNING: Removed duplicated region for block: B:42:0x00a4  */
    /* JADX WARNING: Removed duplicated region for block: B:59:0x00f8  */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private void Cp() {
        /*
        // Method dump skipped, instructions count: 522
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.kth.Cp():void");
    }

    private void Dp() {
        this.fK.acquire();
        synchronized (this.mObject) {
            if (this.wK) {
                this.mEventHandler.sendMessage(this.mEventHandler.obtainMessage(1));
            } else {
                zp();
                try {
                    this.mObject.wait(1000);
                } catch (InterruptedException unused) {
                }
                if (!this.vK) {
                    if (DEBUG) {
                        Log.e(TAG, "sensorProcessMessage(): sensor value change.");
                    }
                    this.mEventHandler.sendMessage(this.mEventHandler.obtainMessage(1));
                }
                yp();
            }
        }
        this.fK.release();
    }

    /* access modifiers changed from: private */
    /* access modifiers changed from: public */
    private void Ep() {
        ContentResolver contentResolver = this.mContext.getContentResolver();
        boolean z = false;
        this.iK = Settings.System.getIntForUser(contentResolver, "oem_acc_blackscreen_gestrue_enable", 0, -2);
        if (DEBUG) {
            Log.d(TAG, "updateH2OemSettings(): mBlackKeySettingState = " + Integer.toHexString(this.iK));
        }
        this.pK = gwm(this.iK, 7) == 1;
        this.kK = gwm(this.iK, 5) == 1;
        this.lK = gwm(this.iK, 4) == 1;
        this.mK = gwm(this.iK, 3) == 1;
        this.nK = gwm(this.iK, 2) == 1;
        this.oK = gwm(this.iK, 1) == 1;
        this.qK = gwm(this.iK, 6) == 1;
        this.rK = gwm(this.iK, 0) == 1;
        if (this.oK) {
            this.nK = true;
        }
        zta(contentResolver);
        int i = this.iK;
        bio.zta(aL, new byte[]{(byte) (i & sis.zta.zta.zta.zta.MAX), (byte) ((i >> 8) & sis.zta.zta.zta.zta.MAX)});
        if (Settings.System.getIntForUser(contentResolver, "oem_acc_anti_misoperation_screen", 0, -2) == 1) {
            z = true;
        }
        this.zK = z;
    }

    private void Ia(boolean z) {
        if (!VibratorSceneUtils.doVibrateWithSceneIfNeeded(this.mContext, this.mVibrator, 26)) {
            Ja(z);
        }
    }

    private void Ja(boolean z) {
        Vibrator vibrator = this.mVibrator;
        if (vibrator != null) {
            vibrator.vibrate(z ? dL : eL, VIBRATION_ATTRIBUTES);
        }
    }

    private boolean Ka(boolean z) {
        Intent intent;
        if (z) {
            return true;
        }
        String str = "net.oneplus.h2launcher";
        if (str.equals(getDefaultHomePackageName(this.mContext))) {
            intent = new Intent("net.oneplus.h2launcher.action.OPEN_QUICK_PAGE");
        } else {
            intent = new Intent("net.oneplus.launcher.action.OPEN_QUICK_PAGE");
            str = com.android.server.wm.you.zta.ufa;
        }
        intent.setPackage(str);
        intent.addFlags(268435456);
        this.mContext.sendBroadcastAsUser(intent, UserHandle.OWNER);
        return true;
    }

    private String getCameraId() throws CameraAccessException {
        String[] cameraIdList = this.uK.getCameraIdList();
        for (String str : cameraIdList) {
            try {
                CameraCharacteristics cameraCharacteristics = this.uK.getCameraCharacteristics(str);
                Boolean bool = (Boolean) cameraCharacteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer num = (Integer) cameraCharacteristics.get(CameraCharacteristics.LENS_FACING);
                if (bool != null && bool.booleanValue() && num != null && num.intValue() == 1) {
                    return str;
                }
            } catch (NullPointerException e) {
                Log.e(TAG, "Couldn't get torch mode characteristics.", e);
            }
        }
        return null;
    }

    private String getDefaultHomePackageName(Context context) {
        String str;
        Intent addCategory = new Intent("android.intent.action.MAIN").addCategory("android.intent.category.HOME");
        PackageManager packageManager = context.getPackageManager();
        if (packageManager == null) {
            str = "getDefaultHomePackageName: could not get package manager";
        } else {
            ResolveInfo resolveActivity = packageManager.resolveActivity(addCategory, 128);
            if (resolveActivity == null) {
                str = "getDefaultHomePackageName: could not get ResolveInfo";
            } else {
                Log.d(TAG, "[isDefaultHome] default home: " + resolveActivity.activityInfo);
                ActivityInfo activityInfo = resolveActivity.activityInfo;
                return activityInfo != null ? activityInfo.packageName : "";
            }
        }
        Log.e(TAG, str);
        return "";
    }

    public static int gwm(int i, int i2) {
        return (i & (1 << i2)) >> i2;
    }

    private void hc(int i) {
        if (this.mSystemReady) {
            MediaSessionLegacyHelper helper = MediaSessionLegacyHelper.getHelper(this.mContext);
            if (helper != null) {
                KeyEvent keyEvent = new KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), 0, i, 0);
                helper.sendMediaButtonEvent(keyEvent, true);
                helper.sendMediaButtonEvent(KeyEvent.changeAction(keyEvent, 1), true);
            } else if (DEBUG) {
                Log.w(TAG, "MediaSessionLegacyHelper instance is null.");
            }
        }
    }

    private void qc(String str) {
        PowerManager.WakeLock wakeLock;
        if (sc(str)) {
            wakeLock = this.hK;
            if (wakeLock == null) {
                return;
            }
        } else {
            wakeLock = this.gK;
            if (wakeLock == null) {
                return;
            }
        }
        wakeLock.acquire(3000);
    }

    /* JADX WARNING: Removed duplicated region for block: B:18:0x003a  */
    /* JADX WARNING: Removed duplicated region for block: B:22:? A[RETURN, SYNTHETIC] */
    /* Code decompiled incorrectly, please refer to instructions dump. */
    private int rc(java.lang.String r4) {
        /*
            r3 = this;
            int r3 = r4.hashCode()
            r0 = -800601068(0xffffffffd047cc14, float:-1.34081618E10)
            r1 = 2
            r2 = 1
            if (r3 == r0) goto L_0x002a
            r0 = -606326130(0xffffffffdbdc328e, float:-1.23960161E17)
            if (r3 == r0) goto L_0x0020
            r0 = 1866289711(0x6f3d522f, float:5.859202E28)
            if (r3 == r0) goto L_0x0016
            goto L_0x0034
        L_0x0016:
            java.lang.String r3 = "OpenCamera"
            boolean r3 = r4.equals(r3)
            if (r3 == 0) goto L_0x0034
            r3 = 0
            goto L_0x0035
        L_0x0020:
            java.lang.String r3 = "FrontCamera"
            boolean r3 = r4.equals(r3)
            if (r3 == 0) goto L_0x0034
            r3 = r2
            goto L_0x0035
        L_0x002a:
            java.lang.String r3 = "TakeVideo"
            boolean r3 = r4.equals(r3)
            if (r3 == 0) goto L_0x0034
            r3 = r1
            goto L_0x0035
        L_0x0034:
            r3 = -1
        L_0x0035:
            r4 = 268435712(0x10000100, float:2.524432E-29)
            if (r3 == 0) goto L_0x0046
            if (r3 == r2) goto L_0x0043
            if (r3 == r1) goto L_0x003f
            goto L_0x0046
        L_0x003f:
            r4 = 268436480(0x10000400, float:2.524663E-29)
            goto L_0x0046
        L_0x0043:
            r4 = 268435968(0x10000200, float:2.524509E-29)
        L_0x0046:
            return r4
        */
        throw new UnsupportedOperationException("Method not decompiled: com.android.server.policy.kth.rc(java.lang.String):int");
    }

    /* JADX INFO: Can't fix incorrect switch cases order, some code will duplicate */
    private boolean sc(String str) {
        char c;
        switch (str.hashCode()) {
            case -1984835376:
                if (str.equals(_K)) {
                    c = 6;
                    break;
                }
                c = 65535;
                break;
            case -800601068:
                if (str.equals(XK)) {
                    c = 3;
                    break;
                }
                c = 65535;
                break;
            case -756103712:
                if (str.equals(YK)) {
                    c = 4;
                    break;
                }
                c = 65535;
                break;
            case -606326130:
                if (str.equals(WK)) {
                    c = 2;
                    break;
                }
                c = 65535;
                break;
            case 49:
                if (str.equals(GK)) {
                    c = 0;
                    break;
                }
                c = 65535;
                break;
            case 401430359:
                if (str.equals(ZK)) {
                    c = 5;
                    break;
                }
                c = 65535;
                break;
            case 1866289711:
                if (str.equals(TK)) {
                    c = 1;
                    break;
                }
                c = 65535;
                break;
            default:
                c = 65535;
                break;
        }
        switch (c) {
            case 0:
            case 1:
            case 2:
            case 3:
            case 4:
            case 5:
            case 6:
                return true;
            default:
                return false;
        }
    }

    private boolean sis(String str, int i, boolean z) {
        Intent launchIntentForPackage = this.mPackageManager.getLaunchIntentForPackage(str);
        if (launchIntentForPackage == null) {
            Log.e(TAG, "start app " + str + " failed because intent is null");
            return false;
        } else if (z) {
            return true;
        } else {
            if ("com.oneplus.soundrecorder".equals(str)) {
                launchIntentForPackage.putExtra("launch_from", "screen_off_gesture");
            }
            this.mContext.startActivityAsUser(launchIntentForPackage, new UserHandle(UserHandle.getUserId(i)));
            return true;
        }
    }

    private void yp() {
        if (DEBUG) {
            Log.d(TAG, "disableProximitySensor() called.");
        }
        if (this.mProximitySensorEnabled) {
            long clearCallingIdentity = Binder.clearCallingIdentity();
            try {
                this.mSensorManager.unregisterListener(this.EK);
                this.mProximitySensorEnabled = false;
            } finally {
                Binder.restoreCallingIdentity(clearCallingIdentity);
            }
        }
    }

    private void zp() {
        if (DEBUG) {
            Log.d(TAG, "enableProximitySensor() called.");
        }
        if (!this.mProximitySensorEnabled) {
            long clearCallingIdentity = Binder.clearCallingIdentity();
            try {
                this.mSensorManager.registerListener(this.EK, this.mProximitySensor, 0);
                this.mProximitySensorEnabled = true;
            } finally {
                Binder.restoreCallingIdentity(clearCallingIdentity);
            }
        }
    }

    private boolean zta(String str, String str2, int i, boolean z) {
        String str3;
        this.sK = (LauncherApps) this.mContext.getSystemService("launcherapps");
        if (this.sK != null) {
            if (z) {
                LauncherApps.ShortcutQuery shortcutQuery = new LauncherApps.ShortcutQuery();
                shortcutQuery.setPackage(str);
                shortcutQuery.setShortcutIds(Arrays.asList(str2));
                if (this.mPackageManager.getLaunchIntentForPackage(str) != null) {
                    try {
                        if (this.sK.getShortcuts(shortcutQuery, new UserHandle(UserHandle.getUserId(i))) != null) {
                            return true;
                        }
                    } catch (IllegalStateException unused) {
                        str3 = "get shortcuts failed";
                    }
                }
            } else {
                try {
                    Bundle bundle = new Bundle();
                    bundle.putBoolean(TAG, true);
                    this.sK.startShortcut(str, str2, null, bundle, new UserHandle(UserHandle.getUserId(i)));
                    return true;
                } catch (ActivityNotFoundException unused2) {
                    str3 = "start shortcut failed";
                }
            }
        }
        str3 = "shortcut service is null";
        Log.e(TAG, str3);
        return false;
    }

    public boolean d(boolean z) {
        if (DEBUG) {
            Log.d(TAG, "setTorchMode() called: " + z);
        }
        synchronized (this) {
            if (this.tK == z) {
                return false;
            }
            this.tK = z;
            try {
                String cameraId = getCameraId();
                CameraManager cameraManager = this.uK;
                if (cameraId == null) {
                    cameraId = fL;
                }
                cameraManager.setTorchMode(cameraId, z);
                return true;
            } catch (CameraAccessException e) {
                Log.e(TAG, "CameraAccessException: Couldn't set torch mode.", e);
                this.tK = false;
                return false;
            }
        }
    }

    /* access modifiers changed from: package-private */
    public boolean isInCall() {
        return this.mTelecomManager.isInCall();
    }

    /* access modifiers changed from: package-private */
    public void kth(String str, String str2) {
        String str3;
        if (str2 != null) {
            zta zta2 = new zta(this, null);
            int indexOf = str2.indexOf(":");
            if (indexOf < 0) {
                zta2.R(str2);
            } else {
                String[] strArr = new String[4];
                strArr[0] = str2.substring(0, indexOf);
                String[] split = str2.substring(indexOf + 1).split(";", 3);
                System.arraycopy(split, 0, strArr, 1, split.length);
                zta2.R(strArr[0]);
                zta2.setPackage(strArr[1]);
                if (ZK.equals(strArr[0])) {
                    str3 = strArr[2];
                } else if (_K.equals(strArr[0])) {
                    zta2.setShortcutId(strArr[2]);
                    str3 = strArr[3];
                }
                zta2.S(str3);
            }
            this.eK.put(str, zta2);
        }
    }

    public void onKeyguardDone() {
        Log.d(TAG, "receive keyguard done, process gesture action");
        this.mHandler.post(new sis(this));
    }

    public void onKeyguardOccludedChangedLw(boolean z) {
        Log.w(TAG, "onKeyguardOccludedChangedLw " + z);
        this.AK = z;
    }

    /* access modifiers changed from: package-private */
    public void onScreenTurnedOff() {
        if (this.wK && this.iK != 0 && !this.mSensorEnabled && (this.BK || this.zK)) {
            this.mSensorEnabled = true;
            this.mSensorManager.registerListener(this.DK, this.mProximitySensor, 0);
        }
        this.mSleeping = true;
        this.xK = null;
    }

    /* access modifiers changed from: package-private */
    public void onStartedWakingUp() {
        if (this.mSensorEnabled && !this.BK) {
            this.mSensorEnabled = false;
            this.mSensorManager.unregisterListener(this.DK);
        }
    }

    public void systemReady() {
        PackageInfo packageInfo;
        this.mSystemReady = true;
        this.mObserver.observe();
        try {
            packageInfo = this.mContext.getPackageManager().getPackageInfo("com.netease.cloudmusic", 0);
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            packageInfo = null;
        }
        if (packageInfo != null) {
            try {
                AppGlobals.getPackageManager().setPackageStoppedState("com.netease.cloudmusic", false, 0);
            } catch (RemoteException e2) {
                e2.printStackTrace();
            }
        }
    }

    /* access modifiers changed from: package-private */
    public void ud() {
        this.mSleeping = false;
        if (this.mSensorEnabled && this.BK) {
            this.mSensorEnabled = false;
            this.mSensorManager.unregisterListener(this.DK);
            if (!this.mPowerManager.isInteractive()) {
                this.mHandler.post(new you(this));
            }
        }
    }

    public void vd() {
        if (DEBUG) {
            Log.d(TAG, "registerCameraManagerCallbacks() called.");
        }
        this.uK.registerTorchCallback(this.FK, this.mHandler);
    }

    /* access modifiers changed from: package-private */
    public void zta(ContentResolver contentResolver) {
        String stringForUser = Settings.System.getStringForUser(contentResolver, "oem_acc_blackscreen_gesture_o", -2);
        String stringForUser2 = Settings.System.getStringForUser(contentResolver, "oem_acc_blackscreen_gesture_v", -2);
        String stringForUser3 = Settings.System.getStringForUser(contentResolver, "oem_acc_blackscreen_gesture_s", -2);
        String stringForUser4 = Settings.System.getStringForUser(contentResolver, "oem_acc_blackscreen_gesture_w", -2);
        String stringForUser5 = Settings.System.getStringForUser(contentResolver, "oem_acc_blackscreen_gesture_m", -2);
        if (stringForUser2 == null && this.rK) {
            stringForUser2 = VK;
        }
        if (stringForUser == null && this.qK) {
            stringForUser = TK;
        }
        kth(NK, stringForUser);
        kth(PK, stringForUser2);
        kth(QK, stringForUser3);
        kth(SK, stringForUser4);
        kth(RK, stringForUser5);
    }

    /* access modifiers changed from: package-private */
    public void zta(KeyguardServiceDelegate keyguardServiceDelegate) {
        this.mKeyguardDelegate = keyguardServiceDelegate;
    }

    public boolean zta(KeyEvent keyEvent) {
        int repeatCount = keyEvent.getRepeatCount();
        boolean z = false;
        if ((keyEvent.getAction() == 1) && repeatCount == 0) {
            z = true;
        }
        if (z) {
            Message obtainMessage = this.mEventHandler.obtainMessage(1);
            if (this.mProximitySensor != null) {
                Dp();
            } else {
                this.mEventHandler.sendMessage(obtainMessage);
            }
        }
        return z;
    }

    /* access modifiers changed from: package-private */
    public boolean zta(zta zta2, boolean z) {
        String action = zta2.getAction();
        if (!z) {
            ActivityManagerServiceInjector.getInstance().setAllowLaunchBackground(this.mHandler, zta2.mPackageName);
        }
        if (action.equals(YK)) {
            return Ka(z);
        }
        if (action.equals(ZK)) {
            return sis(zta2.getPackage(), zta2.getUid(), z);
        }
        if (action.equals(_K)) {
            return zta(zta2.getPackage(), zta2.getShortcutId(), zta2.getUid(), z);
        }
        return false;
    }
}