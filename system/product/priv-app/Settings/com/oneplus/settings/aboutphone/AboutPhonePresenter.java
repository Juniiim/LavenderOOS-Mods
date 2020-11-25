package com.oneplus.settings.aboutphone;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.BidiFormatter;
import android.text.TextUtils;
import android.util.OpFeatures;
import androidx.fragment.app.Fragment;
import com.android.settings.R;
import com.android.settings.Utils;
import com.android.settings.deviceinfo.firmwareversion.FirmwareVersionSettings;
import com.android.settings.password.ChooseLockSettingsHelper;
import com.android.settings.utils.FileSizeFormatter;
import com.android.settingslib.RestrictedLockUtils;
import com.android.settingslib.RestrictedLockUtilsInternal;
import com.android.settingslib.development.DevelopmentSettingsEnabler;
import com.oneplus.settings.SettingsBaseApplication;
import com.oneplus.settings.aboutphone.Contract;
import com.oneplus.settings.utils.OPAuthenticationInformationUtils;
import com.oneplus.settings.utils.OPThemeUtils;
import com.oneplus.settings.utils.OPUtils;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class AboutPhonePresenter implements Contract.Presenter {
    static final int REQUEST_CONFIRM_PASSWORD_FOR_DEV_PREF = 100;
    static final int TAPS_TO_BE_A_DEVELOPER = 7;
    private Activity mActivity;
    private RestrictedLockUtils.EnforcedAdmin mDebuggingFeaturesDisallowedAdmin;
    private boolean mDebuggingFeaturesDisallowedBySystem;
    private int mDevHitCountdown;
    private Fragment mFragment;
    private List<SoftwareInfoEntity> mList = new ArrayList();
    public boolean mProcessingLastDevHit;
    private final UserManager mUm;
    private Contract.View mView;

    public AboutPhonePresenter(Activity context, Fragment fragment, Contract.View view) {
        int i;
        this.mActivity = context;
        this.mView = view;
        this.mFragment = fragment;
        this.mUm = (UserManager) this.mActivity.getSystemService("user");
        this.mDebuggingFeaturesDisallowedAdmin = RestrictedLockUtilsInternal.checkIfRestrictionEnforced(this.mActivity, "no_debugging_features", UserHandle.myUserId());
        if (DevelopmentSettingsEnabler.isDevelopmentSettingsEnabled(this.mActivity)) {
            i = -1;
        } else {
            i = 7;
        }
        this.mDevHitCountdown = i;
        this.mDebuggingFeaturesDisallowedBySystem = RestrictedLockUtilsInternal.hasBaseUserRestriction(this.mActivity, "no_debugging_features", UserHandle.myUserId());
    }

    @Override // com.oneplus.settings.aboutphone.Contract.Presenter
    public void onResume() {
        showHardwareInfo();
        showSoftwareInfo();
    }

    private static boolean isGuaLiftCameraProject() {
        String[] gualiftcameraproject = SettingsBaseApplication.mApplication.getResources().getStringArray(R.array.oneplus_guacamole_lift_camera_project);
        for (int i = 0; i < gualiftcameraproject.length; i++) {
            if (gualiftcameraproject[i] != null && gualiftcameraproject[i].equalsIgnoreCase(Build.MODEL)) {
                return true;
            }
        }
        return false;
    }

    private static boolean is7TMCLVersionProject() {
        if (Build.MODEL.equalsIgnoreCase(SettingsBaseApplication.mApplication.getString(R.string.oneplus_model_19861_for_tmo)) || Build.MODEL.equalsIgnoreCase(SettingsBaseApplication.mApplication.getString(R.string.oneplus_model_19801_for_cn)) || Build.MODEL.equalsIgnoreCase(SettingsBaseApplication.mApplication.getString(R.string.oneplus_model_19801_for_in)) || Build.MODEL.equalsIgnoreCase(SettingsBaseApplication.mApplication.getString(R.string.oneplus_model_19801_for_eu)) || Build.MODEL.equalsIgnoreCase(SettingsBaseApplication.mApplication.getString(R.string.oneplus_model_19801_for_us))) {
            return true;
        }
        return false;
    }

    private void showHardwareInfo() {
        int phoneImageResId;
        if (is7TMCLVersionProject() && OPThemeUtils.isSupportMclTheme()) {
            phoneImageResId = R.drawable.hd_mcl;
        } else if (OPUtils.isHDProject() && !OPUtils.isMEARom()) {
            phoneImageResId = R.drawable.oneplus_other;
        } else if (Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6000) || Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6003)) {
            phoneImageResId = R.drawable.oneplus_6;
        } else if (Build.MODEL.equalsIgnoreCase("ONEPLUS A5000")) {
            phoneImageResId = R.drawable.oneplus_5;
        } else if (Build.MODEL.equalsIgnoreCase("ONEPLUS A5010")) {
            phoneImageResId = R.drawable.oneplus_5t;
        } else if (OPUtils.isOP3()) {
            phoneImageResId = R.drawable.oneplus_3;
        } else if (OPUtils.isOP3T()) {
            phoneImageResId = R.drawable.oneplus_3t;
        } else if (isGuaLiftCameraProject()) {
            phoneImageResId = R.drawable.oneplus_gua_lift_camera;
        } else if (OPUtils.is18857Project()) {
            phoneImageResId = R.drawable.oneplus_18857;
        } else if (!OPAuthenticationInformationUtils.isOlder6tProducts()) {
            phoneImageResId = R.drawable.oneplus_other;
        } else {
            phoneImageResId = R.drawable.oneplus_6;
        }
        this.mView.displayHardWarePreference(phoneImageResId, getCameraInfo(), getCpuName(), getScreenInfo(), getTotalMemory());
    }

    private void showSoftwareInfo() {
        this.mList.clear();
        addDeviceName();
        addModDeveloperInfo();
        if (OPAuthenticationInformationUtils.isNeedAddAuthenticationInfo(this.mActivity)) {
            addAuthenticationInfo();
        }
        addAndroidVersion();
        if (!OPUtils.isSM8150Products()) {
            addOneplusSystemVersion();
        }
        if (!OPUtils.isSupportUss()) {
            addVersionNumber();
        }
        addDeviceModel();
        addLegalInfo();
        addStatusInfo();
        if (!OPAuthenticationInformationUtils.isOlder6tProducts()) {
            if (OPUtils.isO2() || !OPUtils.isSurportProductInfo(this.mActivity)) {
                addAwardInfo();
            } else {
                addProductIntroduce();
                addAwardInfo();
            }
        } else if (!OPUtils.isO2()) {
            addProductIntroduce();
        }
        if (OPUtils.isSupportUss()) {
            addSoftwareVersion();
            addHardwareVersion();
        }
        this.mView.displaySoftWarePreference(this.mList);
    }

    private void addDeviceName() {
        SoftwareInfoEntity deviceName = new SoftwareInfoEntity();
        deviceName.setTitle(this.mActivity.getString(R.string.my_device_info_device_name_preference_title));
        deviceName.setSummary(Settings.System.getString(this.mActivity.getContentResolver(), "oem_oneplus_devicename"));
        deviceName.setResIcon(R.drawable.op_device_name);
        deviceName.setIntent("com.oneplus.intent.OPDeviceNameActivity");
        this.mList.add(deviceName);
    }

    private void addModDeveloperInfo() {
        SoftwareInfoEntity modDeveloper = new SoftwareInfoEntity();
        modDeveloper.setTitle("Modification\ndeveloper");
        modDeveloper.setSummary("@juniiim");
        modDeveloper.setResIcon(R.drawable.op_authentication_information);
        modDeveloper.setIntent(null);
        this.mList.add(modDeveloper);
    }

    private void addAuthenticationInfo() {
        SoftwareInfoEntity authentication = new SoftwareInfoEntity();
        authentication.setSummary(this.mActivity.getString(R.string.oneplus_regulatory_information));
        authentication.setResIcon(R.drawable.op_authentication_information);
        String title = "";
        String intentString = "";
        if (Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_for_china_and_india)) || Build.MODEL.equals(OPAuthenticationInformationUtils.ONEPLUS_A6000) || Build.MODEL.equals("ONEPLUS A5010") || Build.MODEL.equals("ONEPLUS A5000")) {
            if (OPUtils.isO2()) {
                intentString = "android.settings.SHOW_REGULATORY_INFO";
                title = this.mActivity.getString(R.string.regulatory_labels);
            } else {
                intentString = "com.oneplus.intent.OPAuthenticationInformationSettings";
                title = this.mActivity.getString(R.string.oneplus_authentication_information);
            }
        } else if (Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_for_europe_and_america)) || Build.MODEL.equals(OPAuthenticationInformationUtils.ONEPLUS_A6003)) {
            intentString = "android.settings.SHOW_REGULATORY_INFO";
            title = this.mActivity.getString(R.string.regulatory_labels);
        } else if (OPUtils.isOP3() || OPUtils.isOP3T()) {
            if (SystemProperties.get("ro.rf_version").contains("Am")) {
                intentString = "android.settings.SHOW_REGULATORY_INFO";
                title = this.mActivity.getString(R.string.regulatory_labels);
            } else {
                intentString = "com.oneplus.intent.OPAuthenticationInformationSettings";
                title = this.mActivity.getString(R.string.oneplus_authentication_information);
            }
        } else if (Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18821_for_eu)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_18865_for_eu)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_19801_for_eu)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18857_for_eu)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18821_for_us)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18831_for_us)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18857_for_us)) || ((Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_18825_for_us)) && OPUtils.isO2()) || ((Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_oneplus_model_ee145)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_19801_for_us)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_18865_for_us)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_18865_for_tmo)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_19861_for_tmo)) || Build.MODEL.equals(this.mActivity.getString(R.string.oneplus_model_19863_for_tmo))) && !OPUtils.isMEARom()))) {
            intentString = "android.settings.SHOW_REGULATORY_INFO";
            title = this.mActivity.getString(R.string.regulatory_labels);
        } else if (OPAuthenticationInformationUtils.isNeedShowAuthenticationInformation(this.mActivity)) {
            intentString = "com.oneplus.intent.OPAuthenticationInformationSettings";
            title = this.mActivity.getString(R.string.oneplus_authentication_information);
        }
        authentication.setTitle(title);
        authentication.setIntent(intentString);
        this.mList.add(authentication);
    }

    private void addAndroidVersion() {
        SoftwareInfoEntity android2 = new SoftwareInfoEntity();
        android2.setTitle(this.mActivity.getString(R.string.firmware_version));
        android2.setSummary(Build.VERSION.RELEASE);
        android2.setResIcon(R.drawable.op_android_version);
        android2.setIntent("com.android.FirmwareVersionDialogFragment");
        this.mList.add(android2);
    }

    private void addOneplusSystemVersion() {
        String title;
        String summary;
        int resId;
        SoftwareInfoEntity system = new SoftwareInfoEntity();
        if (OpFeatures.isSupport(new int[]{1})) {
            resId = R.drawable.op_o2_version;
            title = this.mActivity.getResources().getString(R.string.oneplus_oxygen_version);
            summary = SystemProperties.get("ro.oxygen.version", this.mActivity.getResources().getString(R.string.device_info_default)).replace("O2", "O₂");
        } else {
            resId = R.drawable.op_h2_version;
            title = this.mActivity.getResources().getString(R.string.oneplus_hydrogen_version).replace("H2", "H₂");
            summary = SystemProperties.get("ro.rom.version", this.mActivity.getResources().getString(R.string.device_info_default)).replace("H2", "H₂");
        }
        system.setTitle(title);
        system.setSummary(summary);
        system.setResIcon(resId);
        system.setIntent(null);
        this.mList.add(system);
    }

    private void addVersionNumber() {
        SoftwareInfoEntity version = new SoftwareInfoEntity();
        version.setTitle(this.mActivity.getString(R.string.build_number));
        String buildNumber = BidiFormatter.getInstance().unicodeWrap(Build.DISPLAY);
        if (OPUtils.isSM8150Products()) {
            buildNumber = SystemProperties.get("ro.rom.version", this.mActivity.getResources().getString(R.string.device_info_default));
        }
        version.setSummary(buildNumber);
        version.setResIcon(R.drawable.op_soft_version);
        version.setIntent("build.number");
        this.mList.add(version);
    }

    private void addDeviceModel() {
        SoftwareInfoEntity model = new SoftwareInfoEntity();
        model.setTitle(this.mActivity.getString(R.string.model_info));
        model.setResIcon(R.drawable.op_model);
        model.setIntent(null);
        if (Build.MODEL.contains("A30") || Build.MODEL.contains("A50") || Build.MODEL.contains("A60")) {
            model.setSummary("ONEPLUS\n" + Build.MODEL.replaceAll("ONEPLUS ", ""));
        } else {
            model.setSummary(Build.MODEL);
        }
        this.mList.add(model);
    }

    private void addLegalInfo() {
        SoftwareInfoEntity legal = new SoftwareInfoEntity();
        legal.setTitle(this.mActivity.getString(R.string.legal_information));
        legal.setSummary(this.mActivity.getString(R.string.oneplus_legal_summary));
        legal.setResIcon(R.drawable.op_legal_settings);
        legal.setIntent("com.oneplus.intent.LegalSettingsActivity");
        this.mList.add(legal);
    }

    private void addStatusInfo() {
        SoftwareInfoEntity status = new SoftwareInfoEntity();
        status.setTitle(this.mActivity.getString(R.string.device_status));
        status.setSummary(this.mActivity.getString(R.string.oneplus_status_summary));
        status.setResIcon(R.drawable.op_status_settings);
        status.setIntent("com.oneplus.intent.MyDeviceInfoFragmentActivity");
        this.mList.add(status);
    }

    private void addAwardInfo() {
        SoftwareInfoEntity award = new SoftwareInfoEntity();
        award.setTitle(this.mActivity.getString(R.string.oneplus_forum_award_title));
        if (OPUtils.isO2()) {
            award.setSummary(this.mActivity.getString(R.string.oneplus_o2_contributors));
        } else {
            award.setSummary(this.mActivity.getString(R.string.oneplus_h2_contributors));
        }
        award.setResIcon(R.drawable.op_award_icon);
        award.setIntent("com.oneplus.intent.OPForumContributorsActivity");
        this.mList.add(award);
    }

    private void addProductIntroduce() {
        SoftwareInfoEntity introduce = new SoftwareInfoEntity();
        introduce.setTitle(this.mActivity.getString(R.string.oneplus_product_info));
        introduce.setSummary(this.mActivity.getString(R.string.oneplus_product_info_summary));
        introduce.setResIcon(R.drawable.op_product_info);
        introduce.setIntent("com.oneplus.action.PRODUCT_INFO");
        this.mList.add(introduce);
    }

    private void addSoftwareVersion() {
        SoftwareInfoEntity sw = new SoftwareInfoEntity();
        sw.setTitle(this.mActivity.getString(R.string.onplus_software_version_info));
        sw.setResIcon(R.drawable.op_software_icon);
        String buildNumber = BidiFormatter.getInstance().unicodeWrap(Build.DISPLAY);
        if (OPUtils.isSM8150Products()) {
            buildNumber = SystemProperties.get("ro.rom.version", this.mActivity.getResources().getString(R.string.device_info_default));
        }
        sw.setSummary(buildNumber);
        sw.setIntent("build.number");
        this.mList.add(sw);
    }

    private void addHardwareVersion() {
        SoftwareInfoEntity hw = new SoftwareInfoEntity();
        hw.setTitle(this.mActivity.getString(R.string.onplus_hardware_version_info));
        hw.setResIcon(R.drawable.op_hardware_icon);
        String defaultVersion = SystemProperties.get("ro.boot.hw_version", this.mActivity.getResources().getString(R.string.device_info_default));
        String hwVersion = Settings.System.getString(this.mActivity.getContentResolver(), "hw_version_ui");
        if (TextUtils.isEmpty(hwVersion)) {
            hwVersion = defaultVersion;
        }
        hw.setSummary(hwVersion);
        hw.setIntent(null);
        this.mList.add(hw);
    }

    private String getCpuName() {
        if (Build.MODEL.startsWith("ONEPLUS A60")) {
            return "Snapdragon™ 845";
        }
        if (Build.MODEL.startsWith("ONEPLUS A50")) {
            return "Snapdragon™ 835";
        }
        if (OPUtils.isOP3T()) {
            return "Snapdragon™ 821";
        }
        if (OPUtils.isOP3()) {
            return "Snapdragon™ 820";
        }
        if (OPUtils.isGuaProject()) {
            return "Snapdragon™ 660";
        }
        if (OPUtils.isHDProject() && !OPUtils.isMEARom()) {
            return this.mActivity.getString(R.string.oneplus_hd_project_cpu_info);
        }
        if (!OPUtils.isOP_19_2nd() || OPUtils.isMEARom()) {
            return "none";
        }
        return this.mActivity.getString(R.string.oneplus_19_2nd_cpu_info);
    }

    private static String formatMemoryDisplay(long size) {
        long mega = (PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID * size) / FileSizeFormatter.MEGABYTE_IN_BYTES;
        int mul = (int) (mega / 512);
        int modulus = (int) (mega % 512);
        if (mul == 0) {
            return mega + "";
        } else if (modulus > 256) {
            int mul2 = mul + 1;
            if (mul2 % 2 == 0) {
                return ((int) (((float) mul2) * 0.5f)) + "";
            }
            return (((float) mul2) * 0.5f) + "";
        } else {
            return ((((float) mul) * 0.5f) + 0.25f) + "";
        }
    }

    private static String getTotalMemory() {
        IOException e;
        String str2 = "";
        FileReader fr = null;
        BufferedReader localBufferedReader = null;
        try {
            fr = new FileReader("/proc/meminfo");
            localBufferedReader = new BufferedReader(fr, 8192);
            String str22 = localBufferedReader.readLine().substring(10).trim();
            str2 = str22.substring(0, str22.length() - 2);
            str2 = str2.trim();
            try {
                localBufferedReader.close();
            } catch (IOException e2) {
                e2.printStackTrace();
            }
            try {
                fr.close();
            } catch (IOException e3) {
                e = e3;
            }
        } catch (IOException e4) {
            e4.printStackTrace();
            if (localBufferedReader != null) {
                try {
                    localBufferedReader.close();
                } catch (IOException e5) {
                    e5.printStackTrace();
                }
            }
            if (fr != null) {
                try {
                    fr.close();
                } catch (IOException e6) {
                    e = e6;
                }
            }
        } catch (Throwable th) {
            if (localBufferedReader != null) {
                try {
                    localBufferedReader.close();
                } catch (IOException e7) {
                    e7.printStackTrace();
                }
            }
            if (fr != null) {
                try {
                    fr.close();
                } catch (IOException e8) {
                    e8.printStackTrace();
                }
            }
            throw th;
        }
        return formatMemoryDisplay(Long.parseLong(str2));
        e.printStackTrace();
        return formatMemoryDisplay(Long.parseLong(str2));
    }

    private String getScreenInfo() {
        if (Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6000) || Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6003)) {
            return this.mActivity.getString(R.string.oneplus_6_28_inch_amoled_display);
        }
        if (Build.MODEL.equalsIgnoreCase("ONEPLUS A5010")) {
            return this.mActivity.getString(R.string.oneplus_6_01_inch_amoled_display);
        }
        if (Build.MODEL.contains("A50") || Build.MODEL.contains("A30")) {
            return this.mActivity.getString(R.string.oneplus_5_5_inch_amoled_display);
        }
        if (Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6010) || Build.MODEL.equalsIgnoreCase(OPAuthenticationInformationUtils.ONEPLUS_A6013) || OPUtils.is18857Project()) {
            return this.mActivity.getString(R.string.oneplus_6_41_inch_amoled_display);
        }
        if (OPUtils.isGuaProject()) {
            this.mActivity.getString(R.string.oneplus_7_pro_screen_info);
            return "6.4\" IPS LCD Display";
        }
        if (!OPUtils.isHDProject() || OPUtils.isMEARom()) {
            return "none";
        }
        return this.mActivity.getString(R.string.oneplus_hd_project_screen_info);
    }

    private String getCameraInfo() {
        if (Build.MODEL.contains("A60") || Build.MODEL.contains("A50")) {
            return "16 + 20 MP Dual Camera";
        }
        if (OPUtils.isOP3T()) {
            return this.mActivity.getString(R.string.oneplus_3t_camera_info);
        }
        if (OPUtils.isOP3()) {
            return this.mActivity.getString(R.string.oneplus_3_camera_info);
        }
        if (OPUtils.is18857Project()) {
            return this.mActivity.getString(R.string.oneplus_18857_camera_info);
        }
        if (OPUtils.isGuaProject()) {
            return this.mActivity.getString(R.string.oneplus_7_camera_info);
        }
        if (OPUtils.isHDProject() && !OPUtils.isMEARom()) {
            return this.mActivity.getString(R.string.oneplus_hd_project_camera_info);
        }
        if (!OPUtils.isOP_19_2nd() || OPUtils.isMEARom()) {
            return "none";
        }
        return this.mActivity.getString(R.string.oneplus_19_2nd_camera_info);
    }

    public void enableDevelopmentSettings() {
        this.mDevHitCountdown = 0;
        this.mProcessingLastDevHit = false;
        DevelopmentSettingsEnabler.setDevelopmentSettingsEnabled(this.mActivity, true);
        this.mView.cancelToast();
        if (OPUtils.isSupportXVibrate()) {
            this.mView.performHapticFeedback();
        }
        this.mView.showLongToast(R.string.show_dev_on);
    }

    @Override // com.oneplus.settings.aboutphone.Contract.Presenter
    public void onItemClick(int position) {
        ComponentName componentName;
        String intent = this.mList.get(position).getIntent();
        if (intent != null && !"".equals(intent)) {
            if ("com.android.FirmwareVersionDialogFragment".equals(intent)) {
                OPUtils.startFragment(this.mActivity, FirmwareVersionSettings.class.getName(), OPUtils.ONEPLUS_METRICSLOGGER);
            } else if (!"build.number".equals(intent)) {
                this.mFragment.startActivity(new Intent(intent));
            } else if (!Utils.isMonkeyRunning()) {
                if ((this.mUm.isAdminUser() || this.mUm.isDemoUser()) && Utils.isDeviceProvisioned(this.mActivity)) {
                    if (this.mUm.hasUserRestriction("no_debugging_features")) {
                        if (this.mUm.isDemoUser() && (componentName = Utils.getDeviceOwnerComponent(this.mActivity)) != null) {
                            Intent requestDebugFeatures = new Intent().setPackage(componentName.getPackageName()).setAction("com.android.settings.action.REQUEST_DEBUG_FEATURES");
                            if (this.mActivity.getPackageManager().resolveActivity(requestDebugFeatures, 0) != null) {
                                this.mActivity.startActivity(requestDebugFeatures);
                                return;
                            }
                        }
                        RestrictedLockUtils.EnforcedAdmin enforcedAdmin = this.mDebuggingFeaturesDisallowedAdmin;
                        if (enforcedAdmin != null && !this.mDebuggingFeaturesDisallowedBySystem) {
                            RestrictedLockUtils.sendShowAdminSupportDetailsIntent(this.mActivity, enforcedAdmin);
                        }
                    }
                    int i = this.mDevHitCountdown;
                    if (i > 0) {
                        this.mDevHitCountdown = i - 1;
                        int i2 = this.mDevHitCountdown;
                        if (i2 != 0 || this.mProcessingLastDevHit) {
                            int i3 = this.mDevHitCountdown;
                            if (i3 > 0 && i3 < 5) {
                                this.mView.cancelToast();
                                Contract.View view = this.mView;
                                Resources resources = this.mActivity.getResources();
                                int i4 = this.mDevHitCountdown;
                                view.showLongToast(resources.getQuantityString(R.plurals.show_dev_countdown, i4, Integer.valueOf(i4)));
                                return;
                            }
                            return;
                        }
                        this.mDevHitCountdown = i2 + 1;
                        this.mProcessingLastDevHit = new ChooseLockSettingsHelper(this.mActivity, this.mFragment).launchConfirmationActivity(100, this.mActivity.getString(R.string.unlock_set_unlock_launch_picker_title));
                        if (!this.mProcessingLastDevHit) {
                            enableDevelopmentSettings();
                        }
                    } else if (i < 0) {
                        this.mView.cancelToast();
                        this.mView.showLongToast(R.string.show_dev_already);
                    }
                }
            }
        }
    }
}