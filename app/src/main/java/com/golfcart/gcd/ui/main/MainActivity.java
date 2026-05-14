package com.golfcart.gcd.ui.main;

import android.os.Bundle;

import androidx.activity.ComponentActivity;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Main entry point Activity for the Golf Cart Computer application.
 * <p>
 * Uses Jetpack Compose for the UI layer. Annotated with @AndroidEntryPoint
 * to enable Hilt dependency injection. The Compose content is set via
 * {@link MainActivityContent} which bridges the Java Activity to the
 * Kotlin Compose UI.
 */
@AndroidEntryPoint
public class MainActivity extends ComponentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        MainActivityContent.INSTANCE.setContent(this);
    }
}
