package com.mrx7014.s25ultraspoofer;

import android.content.Context;
import android.content.Intent;
import android.os.BatteryManager;
import android.os.Bundle;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

public class VoocChargerModule implements IXposedHookLoadPackage {
    
    private static final String TAG = "VoocChargerModule";
    private static final String VOOC_CHARGER_NODE = "/sys/class/power_supply/battery/voocchg_ing";
    private static final String EXTRA_VOOC_CHARGER = "vooc_charger";
    
    @Override
    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        // Hook BatteryService for VOOC detection
        if (lpparam.packageName.equals("android")) {
            hookBatteryService();
        }
        
        // Hook SettingsLib for VOOC charging status
        if (lpparam.packageName.equals("com.android.settings")) {
            hookSettingsLib();
        }
        
        // Hook SystemUI for VOOC indications
        if (lpparam.packageName.equals("com.android.systemui")) {
            hookSystemUI(lpparam.classLoader);
        }
    }
    
    private void hookBatteryService() {
        try {
            XposedBridge.log(TAG + ": Hooking BatteryService");
            
            Class<?> batteryServiceClass = XposedHelpers.findClass(
                "com.android.server.BatteryService", null);
            
            // Hook the updateBatteryInfoLocked method to add VOOC detection
            XposedHelpers.findAndHookMethod(batteryServiceClass, "updateBatteryInfoLocked",
                boolean.class, new XC_MethodHook() {
                    
                    private boolean mVoocCharger = false;
                    private boolean mLastVoocCharger = false;
                    
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        // Check if VOOC charger is connected
                        mVoocCharger = isVoocCharger();
                        
                        // Get the BatteryService instance
                        Object batteryService = param.thisObject;
                        
                        // Get mHealthInfo field
                        Object healthInfo = XposedHelpers.getObjectField(batteryService, "mHealthInfo");
                        
                        // Get mLastBatteryStatus and other fields to check if we should broadcast
                        int lastBatteryStatus = XposedHelpers.getIntField(batteryService, "mLastBatteryStatus");
                        int batteryStatus = XposedHelpers.getIntField(healthInfo, "batteryStatus");
                        
                        // Check if status changed or VOOC status changed
                        boolean force = (Boolean) param.args[0];
                        boolean voocChanged = mVoocCharger != mLastVoocCharger;
                        
                        if (force || voocChanged || batteryStatus != lastBatteryStatus) {
                            // Store the current VOOC status
                            mLastVoocCharger = mVoocCharger;
                        }
                    }
                    
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // The battery info has been updated, we need to modify the broadcast intent
                        Object batteryService = param.thisObject;
                        
                        // Get the mLastBatteryInfoBroadcast field (Intent)
                        Intent lastBroadcast = (Intent) XposedHelpers.getObjectField(
                            batteryService, "mLastBatteryInfoBroadcast");
                        
                        if (lastBroadcast != null) {
                            // Add VOOC charger extra to the broadcast
                            lastBroadcast.putExtra(EXTRA_VOOC_CHARGER, mVoocCharger);
                        }
                    }
                });
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking BatteryService: " + t);
        }
    }
    
    private boolean isVoocCharger() {
        try {
            java.io.FileReader file = new java.io.FileReader(VOOC_CHARGER_NODE);
            java.io.BufferedReader br = new java.io.BufferedReader(file);
            String state = br.readLine();
            br.close();
            file.close();
            return "1".equals(state);
        } catch (java.io.FileNotFoundException e) {
            XposedBridge.log(TAG + ": VOOC charger node not found: " + e.getMessage());
        } catch (java.io.IOException e) {
            XposedBridge.log(TAG + ": Error reading VOOC charger node: " + e.getMessage());
        }
        return false;
    }
    
    private void hookSettingsLib() {
        try {
            XposedBridge.log(TAG + ": Hooking SettingsLib BatteryStatus");
            
            Class<?> batteryStatusClass = XposedHelpers.findClass(
                "com.android.settingslib.fuelgauge.BatteryStatus", null);
            
            // Hook constructor to add voocChargeStatus field
            XposedHelpers.findAndHookConstructor(batteryStatusClass, Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Intent batteryChangedIntent = (Intent) param.args[0];
                        boolean voocChargeStatus = batteryChangedIntent.getBooleanExtra(
                            EXTRA_VOOC_CHARGER, false);
                        
                        // Add voocChargeStatus field
                        XposedHelpers.setAdditionalInstanceField(param.thisObject, 
                            "voocChargeStatus", voocChargeStatus);
                    }
                });
            
            // Hook getChargingSpeed method to handle VOOC charging
            XposedHelpers.findAndHookMethod(batteryStatusClass, "getChargingSpeed",
                Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // Get voocChargeStatus from additional field
                        Boolean voocChargeStatus = (Boolean) XposedHelpers.getAdditionalInstanceField(
                            param.thisObject, "voocChargeStatus");
                        
                        if (voocChargeStatus != null && voocChargeStatus) {
                            // Return CHARGING_VOOC (value 3)
                            param.setResult(3);
                        }
                    }
                });
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking SettingsLib: " + t);
        }
    }
    
    private void hookSystemUI(ClassLoader classLoader) {
        try {
            XposedBridge.log(TAG + ": Hooking SystemUI");
            
            // Hook KeyguardIndicationController to handle VOOC charging strings
            Class<?> keyguardIndicationControllerClass = XposedHelpers.findClass(
                "com.android.systemui.statusbar.KeyguardIndicationController", classLoader);
            
            XposedHelpers.findAndHookMethod(keyguardIndicationControllerClass, 
                "getChargingIndication", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        // We need to modify the charging indication based on charging speed
                        // This is a simplified version - actual implementation would need
                        // to check BatteryStatus.getChargingSpeed()
                        
                        Object batteryStatus = XposedHelpers.getObjectField(
                            param.thisObject, "mBatteryStatus");
                        
                        if (batteryStatus != null) {
                            // Get charging speed
                            int chargingSpeed = XposedHelpers.callMethod(batteryStatus, 
                                "getChargingSpeed", (Object) null);
                            
                            if (chargingSpeed == 3) { // CHARGING_VOOC
                                // Return VOOC charging string
                                // Note: We need to handle string formatting properly
                                param.setResult("VOOC Charging");
                            }
                        }
                    }
                });
            
            // Hook KeyguardUpdateMonitor to handle VOOC charging changes
            Class<?> keyguardUpdateMonitorClass = XposedHelpers.findClass(
                "com.android.keyguard.KeyguardUpdateMonitor", classLoader);
            
            XposedHelpers.findAndHookMethod(keyguardUpdateMonitorClass, 
                "shouldTriggerBatteryStatusUpdate",
                XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus", classLoader),
                XposedHelpers.findClass(
                    "com.android.settingslib.fuelgauge.BatteryStatus", classLoader),
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        Object current = param.args[0];
                        Object old = param.args[1];
                        
                        // Get voocChargeStatus from both BatteryStatus objects
                        Boolean currentVooc = (Boolean) XposedHelpers.getAdditionalInstanceField(
                            current, "voocChargeStatus");
                        Boolean oldVooc = (Boolean) XposedHelpers.getAdditionalInstanceField(
                            old, "voocChargeStatus");
                        
                        // Check if plugged in and VOOC status changed
                        boolean nowPluggedIn = (boolean) XposedHelpers.callMethod(current, "isPluggedIn");
                        
                        if (nowPluggedIn && currentVooc != null && oldVooc != null && 
                            currentVooc != oldVooc) {
                            // Trigger update
                            param.setResult(true);
                        }
                    }
                });
            
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": Error hooking SystemUI: " + t);
        }
    }
}
