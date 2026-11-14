package de.bedrocklan.bridge;

import android.app.Application;
import android.content.Context;

/** Application-Einstieg: richtet die globale Sturzsicherung so früh wie möglich ein. */
public class BridgeApp extends Application {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(LocaleHelper.wrap(base));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Guard.install(this);
    }
}