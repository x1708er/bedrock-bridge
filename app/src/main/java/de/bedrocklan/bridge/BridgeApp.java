package de.bedrocklan.bridge;

import android.app.Application;

/** Application-Einstieg: richtet die globale Sturzsicherung so früh wie möglich ein. */
public class BridgeApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        Guard.install(this);
    }
}