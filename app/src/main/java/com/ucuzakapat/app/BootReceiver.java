package com.ucuzakapat.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Telefon yeniden baslayinca takibi tekrar acar. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Store.load(context).isEmpty()) {
            TrackerService.start(context.getApplicationContext(), true);
        }
    }
}
