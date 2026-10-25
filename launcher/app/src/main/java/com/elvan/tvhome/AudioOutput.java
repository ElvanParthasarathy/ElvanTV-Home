package com.elvan.tvhome;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Where the sound goes: the TV's own speaker, or a paired Bluetooth speaker or headphones. Switching connects or
 * disconnects the device's audio (A2DP) only, so a Bluetooth remote (a different profile) keeps working.
 *
 * Android has no public call for this. connect and disconnect on the A2DP profile are hidden but still reachable on the
 * versions this launcher runs on; if one is refused, the switch is reported as not possible instead of crashing.
 */
final class AudioOutput {
    /** One paired audio device. */
    static final class Sink {
        final BluetoothDevice device;
        final String name;
        final int state;

        Sink(BluetoothDevice device, String name, int state) {
            this.device = device;
            this.name = name;
            this.state = state;
        }

        boolean connected() {
            return state == BluetoothProfile.STATE_CONNECTED;
        }

        boolean connecting() {
            return state == BluetoothProfile.STATE_CONNECTING;
        }
    }

    private final Context context;
    private final Runnable changed;
    private BluetoothAdapter adapter;
    private BluetoothA2dp a2dp;
    private boolean registered;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            changed.run();
        }
    };

    AudioOutput(Context context, Runnable changed) {
        this.context = context.getApplicationContext();
        this.changed = changed;
    }

    /** Binds to the Bluetooth audio service and starts listening for connections coming and going. */
    void open() {
        try {
            adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) return;
            adapter.getProfileProxy(context, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    if (profile == BluetoothProfile.A2DP) {
                        a2dp = (BluetoothA2dp) proxy;
                        changed.run();
                    }
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    if (profile == BluetoothProfile.A2DP) a2dp = null;
                }
            }, BluetoothProfile.A2DP);
            IntentFilter filter = new IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);
            filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
            context.registerReceiver(receiver, filter);
            registered = true;
        } catch (RuntimeException e) {
            adapter = null;   // no permission, or no Bluetooth: the Audio output row is simply not shown
        }
    }

    void close() {
        try {
            if (registered) context.unregisterReceiver(receiver);
            if (adapter != null && a2dp != null) adapter.closeProfileProxy(BluetoothProfile.A2DP, a2dp);
        } catch (RuntimeException ignored) {
        }
        registered = false;
        a2dp = null;
    }

    /** This TV has Bluetooth at all (the Sound output row is shown even with nothing paired, to add a device). */
    boolean available() {
        return adapter != null;
    }

    boolean bluetoothOn() {
        try {
            return adapter != null && adapter.isEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** The paired audio devices, with their current state. Empty when there is no Bluetooth or none is paired. */
    List<Sink> sinks() {
        List<Sink> out = new ArrayList<>();
        try {
            if (adapter == null || !adapter.isEnabled()) return out;
            for (BluetoothDevice device : adapter.getBondedDevices()) {
                BluetoothClass cls = device.getBluetoothClass();
                if (cls == null || cls.getMajorDeviceClass() != BluetoothClass.Device.Major.AUDIO_VIDEO) continue;   // speakers, headphones, car audio
                String name = device.getName();
                int state = a2dp == null ? BluetoothProfile.STATE_DISCONNECTED : a2dp.getConnectionState(device);
                out.add(new Sink(device, name == null || name.isEmpty() ? device.getAddress() : name, state));
            }
        } catch (SecurityException e) {   // the Bluetooth permission was withdrawn: no devices to list
            out.clear();
        } catch (RuntimeException e) {
            out.clear();
        }
        return out;
    }

    /** The device the sound goes to now, or null for the TV's own speaker. */
    Sink active() {
        for (Sink s : sinks()) if (s.connected() || s.connecting()) return s;
        return null;
    }

    /** Sound to the TV's own speaker: every connected Bluetooth audio device is let go. */
    boolean useTv() {
        boolean ok = true;
        for (Sink s : sinks()) if (s.connected() || s.connecting()) ok &= call("disconnect", s.device);
        return ok;
    }

    /** Sound to this Bluetooth device; any other one that was in use is let go first. */
    boolean useBluetooth(Sink target) {
        boolean ok = true;
        for (Sink s : sinks()) {
            if (!s.device.equals(target.device) && (s.connected() || s.connecting())) ok &= call("disconnect", s.device);
        }
        if (!target.connected() && !target.connecting()) ok &= call("connect", target.device);
        return ok;
    }

    /** Gives up on a connection that never came: the system keeps retrying for a long time otherwise. */
    void cancel(BluetoothDevice device) {
        call("disconnect", device);
    }

    private boolean call(String method, BluetoothDevice device) {
        if (a2dp == null) return false;
        try {
            Method m = BluetoothA2dp.class.getMethod(method, BluetoothDevice.class);
            Object result = m.invoke(a2dp, device);
            return !(result instanceof Boolean) || (Boolean) result;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }
}
