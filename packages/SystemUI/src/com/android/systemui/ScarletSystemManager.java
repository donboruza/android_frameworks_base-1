/*
 * Copyright (C) 2019 Descendant
 * Copyright (C) 2022 riceDroid Android Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.AlarmManager.AlarmClockInfo;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.hardware.power.Boost;
import android.hardware.power.Mode;
import android.location.LocationManager;
import android.os.Handler;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.os.PowerManagerInternal;
import android.provider.Settings;

import com.android.server.LocalServices;
import com.android.systemui.Dependency;
import com.android.systemui.statusbar.policy.LocationController;

import java.util.ArrayList;
import java.util.List;

public class ScarletSystemManager {
    static String TAG = "ScarletSystemManager";
    
    static Handler scarletHandler = new Handler();
    static Runnable mStartManagerInstance;
    static Runnable mStopManagerInstance;
    static List<ActivityManager.RunningAppProcessInfo> RunningServices;
    static ActivityManager localActivityManager;
    static Context imContext;
    static ContentResolver mContentResolver;
    static List<String> killablePackages;
    static final long INIT_TIME_NEEDED = 20000;
    static final String SYS_SYSTEM_BGT = "persist.sys.bgt.enable";
    static final String SYS_RENDER_BOOST_THREAD = "persist.sys.perf.topAppRenderThreadBoost.enable";
    static final String SYS_CGROUP_FOLLOW = "persist.sys.cgroup_follow.enable";
    static final String SYS_CGROUP_FOLLOW_DEX2OAT = "persist.sys.fw.cgroup_follow.dex2oat_only";
    static final String SYS_COMPACTION = "persist.sys.appcompact.enable_app_compact";
    static final String SYS_HW_VULKAN = "ro.hardware.vulkan";
    static final String SYS_HWUI = "persist.sys.hwui.renderer";

    public static void initializeSystemServices(Context mContext) {
        imContext = mContext;
        killablePackages = new ArrayList<>();
        localActivityManager = (ActivityManager) mContext.getSystemService(Context.ACTIVITY_SERVICE);
        mContentResolver = mContext.getContentResolver();

        mStartManagerInstance = new Runnable() {
            public void run() {
                    powerSaverHandler(true);
                    killBackgroundProcesses();
            }
        };
        mStopManagerInstance = new Runnable() {
            public void run() {
                stopManager(mContext);
            }
        };
    }

    public static void startSystemIdleServices() {
        RunningServices = localActivityManager.getRunningAppProcesses();

        if (INIT_TIME_NEEDED > timeBeforeAlarm(imContext) && timeBeforeAlarm(imContext) != 0) {
            scarletHandler.postDelayed(mStartManagerInstance,100);
        } else {
            scarletHandler.postDelayed(mStartManagerInstance,INIT_TIME_NEEDED /*20ms*/);
        }
        if (timeBeforeAlarm(imContext) != 0) {
            scarletHandler.postDelayed(mStopManagerInstance,(timeBeforeAlarm(imContext) - 900000));
        }
    }
    
    public static void startBoostingService(boolean enable) {
       SystemProperties.set(SYS_RENDER_BOOST_THREAD, enable ? "true" : "false");
       SystemProperties.set(SYS_SYSTEM_BGT, enable ? "true" : "false");
       SystemProperties.set(SYS_CGROUP_FOLLOW, enable ? "true" : "false");
       SystemProperties.set(SYS_CGROUP_FOLLOW_DEX2OAT, enable ? "true" : "false");
       SystemProperties.set(SYS_COMPACTION, enable ? "false" : "true");
       String vulkanProp = SystemProperties.get(SYS_HW_VULKAN);
       if (!vulkanProp.isEmpty() 
           || (vulkanProp.toString().toLowerCase().contains("adreno"))) {
         SystemProperties.set(SYS_HWUI, enable ? "skiavk" : "skiagl");
       }
    }

    public static void cacheCleaner(PackageManager pm) {
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        for (ApplicationInfo packageInfo : apps) {
            pm.deleteApplicationCacheFiles(packageInfo.packageName, null);
        }
    }

    public static void powerSaverHandler(boolean enable) {
        PowerManagerInternal mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
        if (mLocalPowerManager != null) {
          mLocalPowerManager.setPowerMode(Mode.DEVICE_IDLE, enable);
        }
    }

    public static void stopManager(Context mContext) {
        scarletHandler.removeCallbacks(mStartManagerInstance);
        onScreenWake(mContext);
    }

    public static void onScreenWake(Context mContext) {
        scarletHandler.removeCallbacks(mStopManagerInstance);
        powerSaverHandler(false);
        PowerManagerInternal mLocalPowerManager = LocalServices.getService(PowerManagerInternal.class);
        if (mLocalPowerManager != null) {
          mLocalPowerManager.setPowerBoost(Boost.INTERACTION, 2000);
        }
    }

    public static long timeBeforeAlarm(Context imContext) {
        AlarmManager.AlarmClockInfo info =
                ((AlarmManager)imContext.getSystemService(Context.ALARM_SERVICE)).getNextAlarmClock();
        if (info != null) {
            long alarmTime = info.getTriggerTime();
            long realTime = alarmTime - System.currentTimeMillis();
            return realTime;
        } else {
            return 0;
        }
    }

    public static void killBackgroundProcesses() {
        localActivityManager = (ActivityManager) imContext.getSystemService(Context.ACTIVITY_SERVICE);
        RunningServices = localActivityManager.getRunningAppProcesses();
        for (int i=0; i < RunningServices.size(); i++) {
            if (!RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("com.android.") &&
                !RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("com.ricedroid") &&
                !RunningServices.get(i).pkgList[0].toString().toLowerCase().equals("android") &&
                !RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("launcher") &&
                !RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("ims") &&
                RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("gms") &&
                RunningServices.get(i).pkgList[0].toString().toLowerCase().contains("google")) {
                    localActivityManager.killBackgroundProcesses(RunningServices.get(i).pkgList[0].toString());
            }
        }
    }

}
