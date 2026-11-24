package com.andrerinas.openheadunit.aiways;

import android.content.Intent;

/**
 * The car being switched off and on, as the launcher sees it (Aiways_Luncher CarWatch,
 * CARSET_PWR_STS 3 = on, 1 = off).
 *
 * The Aiways head unit sends none of the ACC intents other units do, so the app never knew the car
 * was going off: the unit kept running for another 20-27 s and then simply lost power in the
 * middle of the session. A wireless session that vanishes like that, without a ByeBye, leaves the
 * phone's Android Auto head unit server (port 5277) waiting for a peer that never comes back; it
 * accepts every later connection and answers none of them ("peer_silent") until the phone is
 * restarted or plugged in once by USB. That is what happened after the drive of 23 November 2026
 * (car off 07:28 mid-session): the whole noon drive of 24 November found no wireless Android Auto
 * until the USB cable went in at 13:21.
 *
 * So the launcher broadcasts {@link #ACTION} with {@link #EXTRA_ON} on every change, guarded by the
 * signature permission {@link #PERMISSION} on both sides, and AapService ends the session with a
 * ByeBye while the link still works and does not reconnect until the car is on again.
 */
public final class AiwaysCarPower {
    public static final String ACTION = "com.aiways.headunit.CAR_POWER";
    public static final String EXTRA_ON = "on";
    public static final String PERMISSION = "com.aiways.permission.HEADUNIT_LINK";
    /** What {@code AccPowerState} records as the reason the car is off. */
    public static final String OFF_REASON = "aiways: launcher CARSET_PWR_STS off";

    private AiwaysCarPower() {
    }

    /**
     * Whether {@code AccPowerState.offAction} is this signal: the launcher said the car is off and
     * nothing has said it is on again. Only this off holds reconnecting back; the ACC intents and
     * the inferred power loss of other units keep their upstream behaviour.
     */
    public static boolean holdsOff(String offAction) {
        return OFF_REASON.equals(offAction);
    }

    /** true = the car was switched on, false = off, null = not a power broadcast. */
    public static Boolean isOn(Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction()) || !intent.hasExtra(EXTRA_ON)) {
            return null;
        }
        return intent.getBooleanExtra(EXTRA_ON, true);
    }
}
