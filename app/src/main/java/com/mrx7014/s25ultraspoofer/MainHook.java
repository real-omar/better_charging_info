package com.mrx7014.s25ultraspoofer;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "VOOCModule: ";
    private static final String VOOC_PATH = "/sys/class/power_supply/battery/voocchg_ing";
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";
    private static final int CHARGING_VOOC = 3;

    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {

        // 1. Hook System Server (BatteryService) to detect VOOC and add to Intent
        if (lpparam.packageName.equals("android")) {
            XposedHelpers.findAndHookMethod("com.android.server.BatteryService", lpparam.classLoader,
                    "sendBatteryChangedIntentLocked", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) XposedHelpers.getObjectField(param.thisObject, "mBatteryChangedIntent");
                            if (intent != null) {
                                boolean isVooc = isVoocActive();
                                intent.putExtra(EXTRA_VOOC_CHARGER, isVooc);
                            }
                        }
                    });
        }

        // 2. Hook SystemUI to interpret the VOOC flag
        if (lpparam.packageName.equals("com.android.systemui")) {
            
            // Hook BatteryStatus constructor to read our custom extra from the Intent
            XposedHelpers.findAndHookConstructor("com.android.settingslib.fuelgauge.BatteryStatus", lpparam.classLoader,
                    Intent.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            boolean isVooc = intent.getBooleanExtra(EXTRA_VOOC_CHARGER, false);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, "voocChargeStatus", isVooc);
                        }
                    });

            // Hook BatteryStatus.getChargingStatus to return our new VOOC ID (3)
            XposedHelpers.findAndHookMethod("com.android.settingslib.fuelgauge.BatteryStatus", lpparam.classLoader,
                    "getChargingStatus", Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Boolean isVooc = (Boolean) XposedHelpers.getAdditionalInstanceField(param.thisObject, "voocChargeStatus");
                            if (isVooc != null && isVooc) {
                                param.setResult(CHARGING_VOOC);
                            }
                        }
                    });

            // Hook KeyguardIndicationController to update the Lockscreen Text
            XposedHelpers.findAndHookMethod("com.android.systemui.statusbar.KeyguardIndicationController", lpparam.classLoader,
                    "computePowerIndication", new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object batteryStatus = XposedHelpers.getObjectField(param.thisObject, "mBatteryStatus");
                            if (batteryStatus == null) return;

                            int status = (int) XposedHelpers.callMethod(batteryStatus, "getChargingStatus", 
                                    XposedHelpers.getObjectField(param.thisObject, "mContext"));

                            if (status == CHARGING_VOOC) {
                                String percentage = (String) XposedHelpers.callMethod(batteryStatus, "formatBatteryPercentage", 
                                        XposedHelpers.getIntField(batteryStatus, "level"));
                                
                                // Manually setting the string since we can't add to R.string
                                String voocText = percentage + " • VOOC Charging";
                                XposedHelpers.setObjectField(param.thisObject, "mPowerIndication", voocText);
                            }
                        }
                    });
        }
    }

    private boolean isVoocActive() {
        try {
            File file = new File(VOOC_PATH);
            if (file.exists()) {
                BufferedReader br = new BufferedReader(new FileReader(file));
                String line = br.readLine();
                br.close();
                return "1".equals(line != null ? line.trim() : "0");
            }
        } catch (Exception e) {
            XposedBridge.log(TAG + "Error reading VOOC node: " + e.getMessage());
        }
        return false;
    }
}
