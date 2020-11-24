package com.oneplus.faceunlock;

import android.content.SharedPreferences;
import com.oneplus.custom.utils.OpCustomizeSettings;
import com.oneplus.faceunlock.utils.Log;
import com.oneplus.faceunlock.utils.Utils;

public class Config {
    public static final boolean DEFAULT_CHECK_LIVENESS_WHEN_SETTING = true;
    public static final boolean DEFAULT_CHECK_LIVENESS_WHEN_UNLOCKING = true;
    public static final boolean DEFAULT_USE_TEE = true;
    private static final String PREF_KEY_CHECK_LIVENESS_WHEN_SETTING = "CheckLivenessWhenSetting";
    private static final String PREF_KEY_CHECK_LIVENESS_WHEN_UNLOCKING = "CheckLivenessWhenUnlocking";
    private static final String PREF_KEY_USE_TEE = "UseTEE";
    private static final String PREF_NAME_CONFIG = "config";
    private static final String SYS_PROP_DEBUG = "persist.faceunlock.debug";
    private static final String SYS_PROP_UNLOCK_DUMP = "persist.camera.unlockdump";
    private static final String SYS_PROP_UNLOCK_DUMP2 = "vendor.oem.camera.unlockdump";
    private static final String TAG = Config.class.getSimpleName();
    private static final SharedPreferences m_ConfigPreferences;
    private static final boolean m_IsDebugMode = "1".equals(Utils.getSystemProperty(SYS_PROP_DEBUG));

    public enum CustomType {
        NONE,
        JCC,
        SW,
        AVG,
        MCL
    }

    static {
        if (m_IsDebugMode) {
            Log.w(TAG, "Debug mode ON");
        }
        if (m_IsDebugMode) {
            m_ConfigPreferences = FaceUnlockApplication.current().getSharedPreferences(PREF_NAME_CONFIG, 0);
        } else {
            m_ConfigPreferences = null;
        }
    }

    protected Config() {
    }

    public static boolean checkLivenessWhenSetting() {
        return selectBooleanConfig(m_ConfigPreferences, PREF_KEY_CHECK_LIVENESS_WHEN_SETTING, true);
    }

    public static boolean checkLivenessWhenUnlocking() {
        return selectBooleanConfig(m_ConfigPreferences, PREF_KEY_CHECK_LIVENESS_WHEN_UNLOCKING, true);
    }

    public static CustomType getCustomType() {
        OpCustomizeSettings.CUSTOM_TYPE customType = OpCustomizeSettings.getCustomType();
        Log.d(TAG, "getCustomType() - CustomType " + customType);
        switch (customType) {
            case JCC:
                return CustomType.JCC;
            case SW:
                return CustomType.SW;
            case AVG:
                return CustomType.AVG;
            case MCL:
                return CustomType.MCL;
            default:
                return CustomType.NONE;
        }
    }

    public static boolean isDebugMode() {
        return m_IsDebugMode;
    }

    public static boolean isUnlockDumpEnabled() {
        if ("1".equals(Utils.getSystemProperty(SYS_PROP_UNLOCK_DUMP)) || "1".equals(Utils.getSystemProperty(SYS_PROP_UNLOCK_DUMP2))) {
            return true;
        }
        return false;
    }

    protected static boolean selectBooleanConfig(SharedPreferences configPref, String configPrefKey, boolean defaultValue) {
        return (!m_IsDebugMode || configPref == null) ? defaultValue : m_ConfigPreferences.getBoolean(configPrefKey, defaultValue);
    }

    protected static float selectFloatConfig(SharedPreferences configPref, String configPrefKey, float defaultValue) {
        return (!m_IsDebugMode || configPref == null) ? defaultValue : m_ConfigPreferences.getFloat(configPrefKey, defaultValue);
    }

    protected static int selectIntConfig(SharedPreferences configPref, String configPrefKey, int defaultValue) {
        return (!m_IsDebugMode || configPref == null) ? defaultValue : m_ConfigPreferences.getInt(configPrefKey, defaultValue);
    }

    public static boolean useHALAutoMode() {
        return false;
    }

    public static boolean useSNPE() {
        return true;
    }

    public static boolean useTEE() {
        return selectBooleanConfig(m_ConfigPreferences, PREF_KEY_USE_TEE, true);
    }
}