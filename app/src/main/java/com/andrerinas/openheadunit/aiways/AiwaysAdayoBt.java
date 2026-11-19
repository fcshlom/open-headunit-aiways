package com.andrerinas.openheadunit.aiways;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reads the address and name of Adayo's Bluetooth module, the one the phone pairs with for calls.
 * Android's own adapter on this unit never comes up, so this module is the only Bluetooth identity
 * the phone knows the car by. Same service and transaction codes as the launcher's
 * BtMusicController (IBTService: getBTName = 30, getBTAddress = 31).
 */
final class AiwaysAdayoBt {
    private static final String DESCRIPTOR = "com.adayo.aidl.IBTService";
    private static final int GET_BT_NAME = 30;
    private static final int GET_BT_ADDRESS = 31;

    /** What the module reported; either field is null when it could not be read. */
    static final class Identity {
        final String address;
        final String name;
        final String error;

        Identity(String address, String name, String error) {
            this.address = address;
            this.name = name;
            this.error = error;
        }
    }

    private AiwaysAdayoBt() {
    }

    /** Blocks up to [timeoutMs] for the service; call off the main thread. */
    static Identity read(Context context, long timeoutMs) {
        final Context app = context.getApplicationContext();
        final CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] holder = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                holder[0] = binder;
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                // read once and unbound right after; nothing to track
            }
        };
        boolean bound;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName("com.adayo.bt", "com.adayo.service.BTService"));
            bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            return new Identity(null, null, "bind failed: " + e);
        }
        if (!bound) {
            return new Identity(null, null, "Adayo's Bluetooth service (com.adayo.bt) is not available");
        }
        try {
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS) || holder[0] == null) {
                return new Identity(null, null, "Adayo's Bluetooth service did not answer");
            }
            String address = transactString(holder[0], GET_BT_ADDRESS);
            String name = transactString(holder[0], GET_BT_NAME);
            return new Identity(address, name, address == null ? "getBTAddress returned nothing" : null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Identity(null, null, "interrupted");
        } finally {
            try {
                app.unbindService(connection);
            } catch (Exception ignored) {
                // already gone
            }
        }
    }

    private static String transactString(IBinder binder, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            binder.transact(code, data, reply, 0);
            reply.readException();
            return reply.readString();
        } catch (Exception e) {
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
