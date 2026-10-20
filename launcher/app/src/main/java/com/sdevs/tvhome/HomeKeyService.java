package com.sdevs.tvhome;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

/**
 * Sends the remote's Home button to this launcher on remotes that emit KEYCODE_ALL_APPS for Home
 * (Xiaomi sticks and boxes). Android TV 9 routes that key only to the system-image launcher, so once
 * the stock launcher is disabled the button does nothing. Every other key passes through untouched.
 *
 * Enable over adb:
 *   settings put secure enabled_accessibility_services com.sdevs.tvhome/.HomeKeyService
 *   settings put secure accessibility_enabled 1
 */
public class HomeKeyService extends AccessibilityService {

    /** Remote buttons that should open Settings (config.xml, settings_key_scancodes and settings_key_keycodes). */
    private int[] settingsScancodes = new int[0];
    private int[] settingsKeycodes = new int[0];

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        settingsScancodes = getResources().getIntArray(R.array.settings_key_scancodes);
        settingsKeycodes = getResources().getIntArray(R.array.settings_key_keycodes);
    }

    private boolean isSettingsKey(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_SETTINGS) return true;
        for (int code : settingsScancodes) if (event.getScanCode() == code) return true;
        for (int code : settingsKeycodes) if (event.getKeyCode() == code) return true;
        return false;
    }

    private boolean isNetflixKey(KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        return scan == 615 || scan == 398 || scan == 580
                || code == KeyEvent.KEYCODE_PROG_RED
                || code == KeyEvent.KEYCODE_BUTTON_1
                || code == KeyEvent.KEYCODE_BUTTON_3;
    }

    private boolean isPrimeKey(KeyEvent event) {
        int code = event.getKeyCode();
        int scan = event.getScanCode();
        return scan == 616 || scan == 399 || scan == 582
                || code == KeyEvent.KEYCODE_PROG_GREEN
                || code == KeyEvent.KEYCODE_BUTTON_2
                || code == KeyEvent.KEYCODE_BUTTON_4;
    }

    private void launchSmartTube() {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("org.smarttube.stable");
            if (i == null) i = getPackageManager().getLaunchIntentForPackage("org.smarttube.stable");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    private void launchNuvio() {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage("com.nuvio.tv");
            if (i == null) i = getPackageManager().getLaunchIntentForPackage("com.nuvio.tv");
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (isNetflixKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                launchSmartTube();
            }
            return true;
        }
        if (isPrimeKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                launchNuvio();
            }
            return true;
        }
        // The stock launcher handled the remote's Settings button; with it disabled the button does nothing.
        if (isSettingsKey(event)) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
            return true;
        }
        if (event.getKeyCode() != KeyEvent.KEYCODE_ALL_APPS) return false;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
            Intent home = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .setPackage(getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(home);
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) {
            String pkg = event.getPackageName().toString();
            if ("com.netflix.ninja".equals(pkg)) {
                launchSmartTube();
            } else if ("com.amazon.amazonvideo.livingroom".equals(pkg)) {
                launchNuvio();
            }
        }
    }

    @Override
    public void onInterrupt() {
    }
}
