package com.andrerinas.openheadunit.aiways;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The launcher's HeadunitLink broadcast (com.aiways.headunit.CONFIG), guarded in the manifest by
 * the signature permission com.aiways.permission.HEADUNIT_LINK: only an app signed like this one
 * can send it. The launcher sends it at start and every 15 min, so it also drives the log upload
 * and the update check ({@link AiwaysSyncService}); no alarm of our own is needed.
 */
public class AiwaysConfigReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.aiways.headunit.CONFIG";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        AiwaysConfig.store(context, intent.getStringExtra("url"), intent.getStringExtra("key"),
                intent.getBooleanExtra("clock_corrected", false), intent.getLongExtra("clock_offset_ms", 0L));
        AiwaysSyncService.start(context);
    }
}
