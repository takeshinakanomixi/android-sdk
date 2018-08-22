package com.tune.location;

import android.Manifest;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.tune.TuneDebugLog;
import com.tune.utils.TuneUtils;

import java.lang.ref.WeakReference;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by johng on 2/9/16.
 */
public class TuneLocationListener implements LocationListener {
    // Max time to listen for location in seconds
    private static final int LISTENER_TIMEOUT = 1000 * 30;
    // Duration in seconds during which the location is considered current
    private static final int LOCATION_VALIDITY_DURATION = 1000 * 60 * 2;
    // Minimum time between updates in seconds
    private static final long MIN_TIME_BETWEEN_UPDATES = 1000 * 5;
    // Minimum distance to update location in meters
    private static final long MIN_DISTANCE_CHANGE_FOR_UPDATES = 10;
    // We're looking for accuracy of within 1000m
    private static final int DESIRED_ACCURACY = 1000;

    private volatile WeakReference<Context> contextReference;
    private volatile LocationManager locationManager;
    private volatile Location lastLocation;
    private volatile Timer timer;
    private volatile boolean listening;

    /**
     * Constructor.
     * @param context Context
     */
    public TuneLocationListener(final Context context) {
        this.contextReference = new WeakReference<>(context);

        // Acquire a reference to the system Location Manager
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    /**
     * Whether app has location permissions or not.
     * @return app has location permissions or not
     */
    private synchronized boolean isLocationEnabled() {
        Context context = contextReference.get();
        if (context == null) {
            return false;
        }

        return TuneUtils.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                || TuneUtils.hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
    }

    /**
     * Gets the last location.
     * Asks for location updates if last location seen is not valid anymore
     * @return last location or null if location wasn't seen yet
     */
    public synchronized Location getLastLocation() {
        // If the last location is null or older than LOCATION_VALIDITY_DURATION, start listening for a new one
        if (lastLocation == null || (System.currentTimeMillis() - lastLocation.getTime()) > LOCATION_VALIDITY_DURATION) {
            // Start listening if not already listening
            if (!listening) {
                TuneDebugLog.d("Last location is null or outdated");
                startListening();
            }
        }
        return lastLocation;
    }

    /**
     * Starts listening for location updates.
     */
    public synchronized void startListening() {
        // If we don't have any location permissions, exit
        if (!isLocationEnabled()) {
            return;
        }

        if (listening) {
            // we are already listening
            return;
        }

        // Set listening status to true;
        listening = true;

        // Location updates must be requested from UI thread
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new GetLocationUpdates(this));
    }

    /**
     * Stops listening for location updates.
     */
    public synchronized void stopListening() {
        TuneDebugLog.d("Stopping listening of location updates");
        // Stop timer if running
        if (timer != null) {
            timer.cancel();
        }
        // Stop receiving location updates
        try {
            locationManager.removeUpdates(this);
        } catch (Exception e) {
            // Catching all exceptions.
        }
        // Set listening status to false
        listening = false;
    }

    /**
     * Checks if this listener is current listening for location changes.
     *
     * @return true if listening, false otherwise
     */
    public synchronized boolean isListening() {
        return listening;
    }

    /**
     * Determines whether one Location reading is better than the current Location fix.
     * Code from http://developer.android.com/guide/topics/location/strategies.html#BestEstimate
     * @param location The new Location that you want to evaluate
     * @param currentBestLocation The current Location fix, to which you want to compare the new one
     * @return Whether new location is better than current best location
     */
    private boolean isBetterLocation(Location location, Location currentBestLocation) {
        if (currentBestLocation == null) {
            // A new location is always better than no location
            return true;
        }

        // Check whether the new location fix is newer or older
        long timeDelta = location.getTime() - currentBestLocation.getTime();
        boolean isSignificantlyNewer = timeDelta > LOCATION_VALIDITY_DURATION;
        boolean isSignificantlyOlder = timeDelta < -LOCATION_VALIDITY_DURATION;
        boolean isNewer = timeDelta > 0;

        // If it's been more than LOCATION_VALIDITY_DURATION seconds since the current location,
        // use the new location because the user has likely moved
        if (isSignificantlyNewer) {
            return true;
            // If the new location is more than LOCATION_VALIDITY_DURATION seconds older, it can't be used
        } else if (isSignificantlyOlder) {
            return false;
        }

        // Check whether the new location fix is more or less accurate
        int accuracyDelta = (int) (location.getAccuracy() - currentBestLocation.getAccuracy());
        boolean isLessAccurate = accuracyDelta > 0;
        boolean isMoreAccurate = accuracyDelta < 0;
        boolean isSignificantlyLessAccurate = accuracyDelta > 200;

        // Check if the old and new location are from the same provider
        boolean isFromSameProvider = isSameProvider(location.getProvider(),
                currentBestLocation.getProvider());

        // Determine location quality using a combination of timeliness and accuracy
        if (isMoreAccurate) {
            return true;
        } else if (isNewer && !isLessAccurate) {
            return true;
        } else {
            return isNewer && !isSignificantlyLessAccurate && isFromSameProvider;
        }
    }

    /**
     * Checks whether two providers are the same.
     * Code from http://developer.android.com/guide/topics/location/strategies.html#BestEstimate
     * @param provider1 First provider to compare
     * @param provider2 Second provider to compare
     * @return Whether they're the same
     */
    private boolean isSameProvider(String provider1, String provider2) {
        if (provider1 == null) {
            return provider2 == null;
        }
        return provider1.equals(provider2);
    }

    private class GetLocationUpdates implements Runnable {
        private final LocationListener listener;

        GetLocationUpdates(LocationListener listener) {
            this.listener = listener;
        }

        @Override
        public void run() {
            TuneDebugLog.d("Listening for location updates");

            try {
                // Request updates from GPS and network
                // GPS requires ACCESS_FINE_LOCATION
                boolean hasFineLocationPermission = false;

                Context context = contextReference.get();
                if (context != null) {
                    hasFineLocationPermission = TuneUtils.hasPermission(context, Manifest.permission.ACCESS_FINE_LOCATION);
                }

                if (hasFineLocationPermission) {
                    if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                        // Initialize to last known GPS location, in case we never get updates on location
                        if (lastLocation == null) {
                            lastLocation = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                        }
                        // Register this class with the Location Manager to receive GPS location updates
                        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, MIN_TIME_BETWEEN_UPDATES, MIN_DISTANCE_CHANGE_FOR_UPDATES, listener);
                    }
                }

                // Network requires ACCESS_COARSE_LOCATION
                boolean hasCoarseLocationPermission = TuneUtils.hasPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION);
                boolean hasNetworkProviderPermissions = hasCoarseLocationPermission;
                // Work around a location-related crash on OnePlus2 devices where accessing the network provider also requires ACCESS_FINE_LOCATION:
                // https://chromium.googlesource.com/chromium/src/+/c13ab13e3dccb32be5a376b237a81ed326e158c0%5E%21/#F0
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    hasNetworkProviderPermissions = hasCoarseLocationPermission && hasFineLocationPermission;
                }

                if (hasNetworkProviderPermissions) {
                    if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                        // Use last network location if not initialized and GPS location not found
                        if (lastLocation == null) {
                            lastLocation = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                        }
                        // Register this class with the Location Manager to receive network + wifi location updates
                        locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, MIN_TIME_BETWEEN_UPDATES, MIN_DISTANCE_CHANGE_FOR_UPDATES, listener);
                    }
                }

                // Stop listening after LISTENER_TIMEOUT if onLocationChanged is never received
                timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        TuneDebugLog.d("Location timer timed out");
                        stopListening();
                    }
                }, LISTENER_TIMEOUT);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    public void onLocationChanged(Location location) {
        if (location != null) {
            try {
                // New location is found by the location provider
                // Note that this will throw an exception if the Location object is not valid
                // (see Exception Note below).
                TuneDebugLog.v("Received new location " + location.toString());

                // Update the lastLocation if the new one is better
                if (isBetterLocation(location, lastLocation)) {
                    TuneDebugLog.v("New location is better, saving");
                    lastLocation = location;
                }

                // If we got a location of less than 1km accuracy, stop listening
                // Otherwise keep listening for new location updates
                if (location.getAccuracy() <= DESIRED_ACCURACY) {
                    stopListening();
                }
            } catch (Exception e) {
                // Note -- Some devices have triggered Location Change events that cause us to
                // throw exceptions (ExceptionInInitializerError) when trying to access the object,
                // which indicates that the given Location object is not valid.
                TuneDebugLog.e("Location exception", e);
            }
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
        TuneDebugLog.d("onStatusChanged: " + provider + ". Status = " + status);
    }

    @Override
    public void onProviderEnabled(String provider) {
        TuneDebugLog.d("onProviderEnabled: " + provider);
    }

    @Override
    public void onProviderDisabled(String provider) {
        TuneDebugLog.d("onProviderDisabled: " + provider);
    }
}
