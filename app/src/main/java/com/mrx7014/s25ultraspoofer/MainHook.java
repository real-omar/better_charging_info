package com.mrx7014.s25ultraspoofer;

import android.content.Context;
import android.content.Intent;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class MainHook implements IXposedHookLoadPackage {
    
    private static final String TAG = "VoocChargerModule";
    private static final String VOOC_CHARGER_NODE = "/sys/class/power_supply/battery/voocchg_ing";
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";
    
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // Hook BatteryService in android package
        if ("android".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + ": Hooking android package");
            hookBatteryService(lpparam.classLoader);
        }
        
        // Hook SystemUI for charging indications
        if ("com.android.systemui".equals(lpparam.packageName)) {
            XposedBridge.log(TAG + ": Hooking SystemUI package");
            hookSystemUI(lpparam.classLoader);
        }
    }
    
    private void hookBatteryService(ClassLoader classLoader) {
        try {
            XposedBridge.log(TAG + ": Looking for BatteryService class");
            
            Class<?> batteryServiceClass = XposedHelpers.findClassIfExists(
                "com.android.server.BatteryService", classLoader);
            
            if (batteryServiceClass == null) {
                XposedBridge.log(TAG + ": BatteryService class not found");
                return;
            }
            
            XposedBridge.log(TAG + ": Found BatteryService, hooking methods");
            
            // Hook the method that processes battery updates
            // Note: The actual method name might vary - adjust as needed
            XposedHelpers.findAndHookMethod(batteryServiceClass, "update", 
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        try {
                            boolean isVoocCharging = checkVoocCharger();
                            XposedBridge.log(TAG + ": VOOC charging status: " + isVoocCharging);
                            
                            // Store VOOC status in the BatteryService instance
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, 
                                "voocChargerStatus", isVoocCharging);
                        } catch (Exception e) {
                            XposedBridge.log(TAG + ": Error checking VOOC: " + e);
                        }
                    }
                });
            
            // Hook the method that sends battery changed broadcasts
            // This method might have a different name - common ones are:
            // "sendIntentLocked", "sendBatteryChangedIntent", "sendBroadcast"
            String[] possibleMethods = {"sendBatteryChangedIntent", "sendIntentLocked", "sendBroadcast"};
            
            for (String methodName : possibleMethods) {
                try {
                    XposedHelpers.findAndHookMethod(batteryServiceClass, methodName,
                        Intent.class, new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    // Get the intent that will be sent
                                    Intent intent = (Intent) param.args[0];
                                    if (intent != null && Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                                        // Get VOOC status from the BatteryService instance
                                        Boolean voocStatus = (Boolean) XposedHelpers.getAdditionalInstanceField(
                                            param.thisObject, "voocChargerStatus");
                                        
                                        if (voocStatus != null) {
                                            intent.putExtra(EXTRA_VOOC_CHARGER, voocStatus);
                                            XposedBridge.log(TAG + ": Added VOOC extra to broadcast: " + voocStatus);
                                        }
                                    }
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Error adding VOOC extra: " + e);
                                }
                            }
                        });
                    XposedBridge.log(TAG + ": Successfully hooked method: " + methodName);
                    break;
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": Method " + methodName + " not found, trying next...");
                }
            }
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking BatteryService: " + t);
        }
    }
    
    private void hookSystemUI(ClassLoader classLoader) {
        try {
            // Hook BatteryStatus in SystemUI (might be different from SettingsLib)
            Class<?> batteryStatusClass = XposedHelpers.findClassIfExists(
                "com.android.settingslib.fuelgauge.BatteryStatus", classLoader);
            
            if (batteryStatusClass != null) {
                XposedBridge.log(TAG + ": Found BatteryStatus in SystemUI");
                
                // Hook BatteryStatus constructor to handle VOOC extra
                XposedHelpers.findAndHookConstructor(batteryStatusClass, Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Intent intent = (Intent) param.args[0];
                            boolean voocCharger = intent.getBooleanExtra(EXTRA_VOOC_CHARGER, false);
                            XposedHelpers.setAdditionalInstanceField(param.thisObject, 
                                "voocChargeStatus", voocCharger);
                        }
                    });
                
                // Hook getChargingSpeed to handle VOOC
                XposedHelpers.findAndHookMethod(batteryStatusClass, "getChargingSpeed", 
                    Context.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Boolean voocStatus = (Boolean) XposedHelpers.getAdditionalInstanceField(
                                param.thisObject, "voocChargeStatus");
                            
                            if (voocStatus != null && voocStatus) {
                                // Return 3 for VOOC charging (matching the patch)
                                param.setResult(3);
                                XposedBridge.log(TAG + ": Returning CHARGING_VOOC (3)");
                            }
                        }
                    });
            }
            
            // Hook KeyguardIndicationController for showing VOOC text
            Class<?> indicationControllerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.KeyguardIndicationController", classLoader);
            
            if (indicationControllerClass != null) {
                XposedBridge.log(TAG + ": Found KeyguardIndicationController");
                
                // Hook the method that shows charging info
                // Method name might vary - common ones: "updateChargingInfo", "updateIndication"
                try {
                    XposedHelpers.findAndHookMethod(indicationControllerClass, "updateChargingInfo",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    // Get battery status
                                    Object batteryStatus = XposedHelpers.getObjectField(param.thisObject, "mBatteryStatus");
                                    if (batteryStatus != null) {
                                        // Get charging speed - FIXED TYPE CAST
                                        Object chargingSpeedObj = XposedHelpers.callMethod(batteryStatus, "getChargingSpeed", 
                                            XposedHelpers.getObjectField(param.thisObject, "mContext"));
                                        int chargingSpeed = (Integer) chargingSpeedObj;
                                        
                                        if (chargingSpeed == 3) { // VOOC charging
                                            // Get current charge percentage
                                            int level = XposedHelpers.getIntField(batteryStatus, "level");
                                            
                                            // Create custom indication text
                                            String voocText = level + "% • VOOC Charging";
                                            
                                            // Try to update the indication
                                            try {
                                                XposedHelpers.callMethod(param.thisObject, "updateIndication", voocText);
                                            } catch (Exception e) {
                                                // Try alternative method
                                                XposedHelpers.setObjectField(param.thisObject, "mTopIndication", voocText);
                                            }
                                            
                                            // Prevent default behavior
                                            param.setResult(null);
                                        }
                                    }
                                } catch (Exception e) {
                                    XposedBridge.log(TAG + ": Error in updateChargingInfo: " + e);
                                }
                            }
                        });
                } catch (NoSuchMethodError e) {
                    XposedBridge.log(TAG + ": updateChargingInfo not found, trying alternatives");
                    
                    // Try hooking updateIndication directly
                    try {
                        XposedHelpers.findAndHookMethod(indicationControllerClass, "updateIndication",
                            String.class, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    // Check if this is a charging indication
                                    String currentText = (String) param.args[0];
                                    if (currentText != null && currentText.contains("Charging")) {
                                        // We could modify it here if we detect VOOC
                                        // But we need access to battery status
                                    }
                                }
                            });
                    } catch (NoSuchMethodError e2) {
                        XposedBridge.log(TAG + ": Alternative methods also not found");
                    }
                }
            }
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking SystemUI: " + t);
        }
    }
    
    private boolean checkVoocCharger() {
        try (BufferedReader br = new BufferedReader(new FileReader(VOOC_CHARGER_NODE))) {
            String state = br.readLine();
            if (state != null) {
                return "1".equals(state.trim());
            }
        } catch (IOException e) {
            XposedBridge.log(TAG + ": Cannot read VOOC charger node: " + e.getMessage());
        }
        return false;
    }
}
