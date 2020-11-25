package com.oneplus.settings;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.os.Bundle;
import android.provider.SearchIndexableResource;
import android.provider.Settings;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;
import com.android.settings.R;
import com.android.settings.dashboard.DashboardFragment;
import com.android.settings.dashboard.SummaryLoader;
import com.android.settings.search.BaseSearchIndexProvider;
import com.android.settings.search.Indexable;
import com.android.settingslib.core.AbstractPreferenceController;
import com.oneplus.settings.gestures.OPAssistantAPPSwitchPreferenceController;
import com.oneplus.settings.gestures.OPQuickTurnOnAssistantAppPreferenceController;
import com.oneplus.settings.utils.OPUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class OPButtonsAndGesturesSettings extends DashboardFragment implements Preference.OnPreferenceChangeListener, Indexable {
    private static final String FINGERPRINT_GESTURE_SWIPE_DOWN_UP_KEY = "op_fingerprint_gesture_swipe_down_up";
    private static final String FINGERPRINT_LONG_PRESS_CAMERA_SHOT_KEY = "op_fingerprint_long_press_camera_shot";
    private static final String KEY_ALERTSLIDER_SETTINGS_SOC_TRI_STATE = "op_alertslider_settings_soc_tri_state";
    private static final String KEY_BUTTONS_AND_FULLSCREEN_GESTURES = "op_buttons_and_fullscreen_gestures";
    private static final String KEY_BUTTONS_SETTINGS = "buttons_settings";
    private static final String KEY_CAMERA_DOUBLE_TAP_POWER_GESTURE = "camera_double_tap_power_gesture";
    private static final int ONEPLUS_EMERGENCY_TAP_POWER_GESTURE_FIVE_TIMES = 5;
    private static final int ONEPLUS_EMERGENCY_TAP_POWER_GESTURE_NO_TIMES = -1;
    private static final int ONEPLUS_EMERGENCY_TAP_POWER_GESTURE_THREE_TIMES = 3;
    public static final BaseSearchIndexProvider SEARCH_INDEX_DATA_PROVIDER = new BaseSearchIndexProvider() {
        /* class com.oneplus.settings.OPButtonsAndGesturesSettings.AnonymousClass1 */

        @Override // com.android.settings.search.BaseSearchIndexProvider, com.android.settings.search.Indexable.SearchIndexProvider
        public List<SearchIndexableResource> getXmlResourcesToIndex(Context context, boolean enabled) {
            SearchIndexableResource sir = new SearchIndexableResource(context);
            if (!OPUtils.isGuestMode()) {
                sir.xmlResId = R.xml.op_buttons_and_gesture_settings;
            }
            return Arrays.asList(sir);
        }

        @Override // com.android.settings.search.BaseSearchIndexProvider, com.android.settings.search.Indexable.SearchIndexProvider
        public List<String> getNonIndexableKeys(Context context) {
            super.getNonIndexableKeys(context);
            List<String> result = new ArrayList<>();
            if (OPUtils.isSupportCustomFingerprint()) {
                result.add(OPButtonsAndGesturesSettings.FINGERPRINT_LONG_PRESS_CAMERA_SHOT_KEY);
            }
            if (OPUtils.isSurportBackFingerprint(context) && !OPUtils.isSupportGesturePullNotificationBar()) {
                result.add(OPButtonsAndGesturesSettings.FINGERPRINT_GESTURE_SWIPE_DOWN_UP_KEY);
            }
            if (OPButtonsAndGesturesSettings.isSupportHardwareKeys()) {
                result.add(OPButtonsAndGesturesSettings.KEY_BUTTONS_AND_FULLSCREEN_GESTURES);
            } else {
                result.add(OPButtonsAndGesturesSettings.KEY_BUTTONS_SETTINGS);
            }
            if (OPUtils.isO2()) {
                result.add("double_tap_power_gesture");
            } else {
                result.add(OPButtonsAndGesturesSettings.KEY_CAMERA_DOUBLE_TAP_POWER_GESTURE);
            }
            return result;
        }
    };
    public static final SummaryLoader.SummaryProviderFactory SUMMARY_PROVIDER_FACTORY = new SummaryLoader.SummaryProviderFactory() {
        /* class com.oneplus.settings.OPButtonsAndGesturesSettings.AnonymousClass2 */

        @Override // com.android.settings.dashboard.SummaryLoader.SummaryProviderFactory
        public SummaryLoader.SummaryProvider createSummaryProvider(Activity activity, SummaryLoader summaryLoader) {
            return new SummaryProvider(activity, summaryLoader);
        }
    };
    private static final String TAG = "OPOthersSettings";
    private Preference mAlertsliderSettingsPreference;
    private Preference mButtonsAndFullscreenGesturesPreference;
    private Preference mButtonsSettingsPreference;
    private SwitchPreference mCameraDoubleTapPowerGesturePreference;
    private Context mContext;
    private SwitchPreference mFingerprintGestureLongpressCamera;
    private SwitchPreference mFingerprintGestureSwipeDownUp;

    @Override // com.android.settings.SettingsPreferenceFragment, androidx.preference.PreferenceFragmentCompat, com.android.settings.dashboard.DashboardFragment, androidx.fragment.app.Fragment, com.android.settingslib.core.lifecycle.ObservablePreferenceFragment
    public void onCreate(Bundle icicle) {
        Preference preference;
        super.onCreate(icicle);
        this.mContext = SettingsBaseApplication.mApplication;
        this.mCameraDoubleTapPowerGesturePreference = (SwitchPreference) findPreference(KEY_CAMERA_DOUBLE_TAP_POWER_GESTURE);
        if (!isCameraDoubleTapPowerGestureAvailable(getResources()) || !OPUtils.isO2()) {
            removePreference(KEY_CAMERA_DOUBLE_TAP_POWER_GESTURE);
        } else {
            this.mCameraDoubleTapPowerGesturePreference.setOnPreferenceChangeListener(this);
        }
        this.mFingerprintGestureLongpressCamera = (SwitchPreference) findPreference(FINGERPRINT_LONG_PRESS_CAMERA_SHOT_KEY);
        this.mFingerprintGestureLongpressCamera.setOnPreferenceChangeListener(this);
        this.mFingerprintGestureSwipeDownUp = (SwitchPreference) findPreference(FINGERPRINT_GESTURE_SWIPE_DOWN_UP_KEY);
        this.mFingerprintGestureSwipeDownUp.setOnPreferenceChangeListener(this);
        if (!OPUtils.isSurportBackFingerprint(this.mContext) || OPUtils.isSupportCustomFingerprint()) {
            this.mFingerprintGestureLongpressCamera.setVisible(false);
            if (!OPUtils.isSupportGesturePullNotificationBar()) {
                this.mFingerprintGestureSwipeDownUp.setVisible(false);
            }
        } else if (!OPUtils.isSupportGesturePullNotificationBar()) {
            this.mFingerprintGestureSwipeDownUp.setVisible(false);
        }
        this.mAlertsliderSettingsPreference = findPreference(KEY_ALERTSLIDER_SETTINGS_SOC_TRI_STATE);
        if (!OPUtils.isSupportSocTriState() && (preference = this.mAlertsliderSettingsPreference) != null) {
            preference.setTitle(R.string.alertslider_settings);
        }
        this.mButtonsAndFullscreenGesturesPreference = findPreference(KEY_BUTTONS_AND_FULLSCREEN_GESTURES);
        this.mButtonsSettingsPreference = findPreference(KEY_BUTTONS_SETTINGS);
        if (isSupportHardwareKeys()) {
            this.mButtonsAndFullscreenGesturesPreference.setVisible(false);
        } else {
            this.mButtonsSettingsPreference.setVisible(false);
        }
        removePreference(KEY_ALERTSLIDER_SETTINGS_SOC_TRI_STATE);
        removePreference(FINGERPRINT_LONG_PRESS_CAMERA_SHOT_KEY);
    }

    /* access modifiers changed from: private */
    public static boolean isSupportHardwareKeys() {
        return !SettingsBaseApplication.mApplication.getResources().getBoolean(17891518);
    }

    /* access modifiers changed from: protected */
    @Override // com.android.settings.dashboard.DashboardFragment
    public String getLogTag() {
        return TAG;
    }

    /* access modifiers changed from: protected */
    @Override // com.android.settings.core.InstrumentedPreferenceFragment, com.android.settings.dashboard.DashboardFragment
    public int getPreferenceScreenResId() {
        return R.xml.op_buttons_and_gesture_settings;
    }

    private static boolean isCameraDoubleTapPowerGestureAvailable(Resources res) {
        return res.getBoolean(17891384);
    }

    private void loadPreferenceScreen() {
        boolean z = false;
        boolean inEmergencyCall = Settings.Global.getInt(getContentResolver(), "emergency_affordance_needed", 0) != 0;
        int times = Settings.Global.getInt(getContentResolver(), "oneplus_emergency_tap_power_gesture_times", -1);
        if (times == -1) {
            if (inEmergencyCall) {
                times = 3;
            } else {
                times = 5;
            }
        }
        if (times == 3) {
            this.mCameraDoubleTapPowerGesturePreference.setEnabled(false);
            this.mCameraDoubleTapPowerGesturePreference.setSummary(R.string.oneplus_emergency_tap_power_gesture_tips);
        } else {
            this.mCameraDoubleTapPowerGesturePreference.setEnabled(true);
            this.mCameraDoubleTapPowerGesturePreference.setSummary(R.string.camera_double_tap_power_gesture_title);
        }
        if (this.mCameraDoubleTapPowerGesturePreference != null) {
            int value = Settings.Secure.getInt(getContentResolver(), "camera_double_tap_power_gesture_disabled", 0);
            SwitchPreference switchPreference = this.mCameraDoubleTapPowerGesturePreference;
            if (value == 0) {
                z = true;
            }
            switchPreference.setChecked(z);
        }
        SwitchPreference switchPreference2 = this.mFingerprintGestureLongpressCamera;
        if (switchPreference2 != null) {
            switchPreference2.setChecked(isFingerprintLongpressCameraShotEnabled(this.mContext));
        }
        SwitchPreference switchPreference3 = this.mFingerprintGestureSwipeDownUp;
        if (switchPreference3 != null) {
            switchPreference3.setChecked(isSystemUINavigationEnabled(this.mContext));
        }
    }

    private static boolean isSystemUINavigationEnabled(Context context) {
        return Settings.Secure.getInt(context.getContentResolver(), "system_navigation_keys_enabled", 0) == 1;
    }

    private static boolean isFingerprintLongpressCameraShotEnabled(Context context) {
        return Settings.System.getInt(context.getContentResolver(), FINGERPRINT_LONG_PRESS_CAMERA_SHOT_KEY, 0) == 1;
    }

    @Override // com.android.settings.SettingsPreferenceFragment, com.android.settings.core.InstrumentedPreferenceFragment, com.android.settings.dashboard.DashboardFragment, androidx.fragment.app.Fragment, com.android.settingslib.core.lifecycle.ObservablePreferenceFragment
    public void onResume() {
        super.onResume();
        loadPreferenceScreen();
    }

    /* JADX WARN: Multi-variable type inference failed */
    /* JADX WARN: Type inference failed for: r1v18 */
    /* JADX WARN: Type inference failed for: r1v19 */
    /* JADX WARN: Type inference failed for: r1v20 */
    /* JADX WARNING: Unknown variable types count: 1 */
    @Override // androidx.preference.Preference.OnPreferenceChangeListener
    /* Code decompiled incorrectly, please refer to instructions dump. */
    public boolean onPreferenceChange(androidx.preference.Preference r7, java.lang.Object r8) {
        /*
        // Method dump skipped, instructions count: 107
        */
        throw new UnsupportedOperationException("Method not decompiled: com.oneplus.settings.OPButtonsAndGesturesSettings.onPreferenceChange(androidx.preference.Preference, java.lang.Object):boolean");
    }

    @Override // androidx.fragment.app.Fragment, com.android.settingslib.core.lifecycle.ObservablePreferenceFragment
    public void onDestroy() {
        super.onDestroy();
    }

    @Override // com.android.settingslib.core.instrumentation.Instrumentable
    public int getMetricsCategory() {
        return OPUtils.ONEPLUS_METRICSLOGGER;
    }

    /* access modifiers changed from: protected */
    @Override // com.android.settings.dashboard.DashboardFragment
    public List<AbstractPreferenceController> createPreferenceControllers(Context context) {
        List<AbstractPreferenceController> controllers = new ArrayList<>();
        OPAssistantAPPSwitchPreferenceController mOPVoiceAssistantSwitchPreferenceController = new OPAssistantAPPSwitchPreferenceController(context);
        getSettingsLifecycle().addObserver(mOPVoiceAssistantSwitchPreferenceController);
        controllers.add(mOPVoiceAssistantSwitchPreferenceController);
        OPQuickTurnOnAssistantAppPreferenceController mOPQuickTurnOnAssistantAppPreferenceController = new OPQuickTurnOnAssistantAppPreferenceController(context, getSettingsLifecycle());
        getSettingsLifecycle().addObserver(mOPQuickTurnOnAssistantAppPreferenceController);
        controllers.add(mOPQuickTurnOnAssistantAppPreferenceController);
        return controllers;
    }

    private static class SummaryProvider implements SummaryLoader.SummaryProvider {
        private final Context mContext;
        private final SummaryLoader mLoader;

        private SummaryProvider(Context context, SummaryLoader loader) {
            this.mContext = context;
            this.mLoader = loader;
        }

        @Override // com.android.settings.dashboard.SummaryLoader.SummaryProvider
        public void setListening(boolean listening) {
            if (listening) {
                updateSummary();
            }
        }

        private void updateSummary() {
            if (OPUtils.isSupportSocTriState()) {
                this.mLoader.setSummary(this, this.mContext.getString(R.string.oneplus_buttons_dashboard_summary));
                return;
            }
            String summary = this.mContext.getString(R.string.alertslider_settings);
            String navkeysSummary = this.mContext.getString(R.string.buttons_enable_on_screen_navkeys_title).toLowerCase();
            String summary2 = this.mContext.getString(R.string.join_many_items_middle, summary, navkeysSummary);
            String quickgestureSummary = this.mContext.getString(R.string.oneplus_quick_gestures).toLowerCase();
            this.mLoader.setSummary(this, this.mContext.getString(R.string.join_many_items_middle, summary2, quickgestureSummary));
        }
    }
}