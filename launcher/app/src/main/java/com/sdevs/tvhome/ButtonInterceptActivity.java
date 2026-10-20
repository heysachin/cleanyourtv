package com.sdevs.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Intercepts dedicated remote buttons (Netflix / Prime Video) and launches
 * SmartTube or Nuvio instead.
 */
public class ButtonInterceptActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        if ("com.amazon.amazonvideo.livingroom.AMAZON_BUTTON".equals(action)) {
            launch("com.nuvio.tv");
        } else {
            launch("org.smarttube.stable");
        }
        finish();
    }

    private void launch(String pkg) {
        try {
            Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                startActivity(i);
            }
        } catch (Exception ignored) {}
    }
}
