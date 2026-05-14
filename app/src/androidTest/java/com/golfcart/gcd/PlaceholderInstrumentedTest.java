package com.golfcart.gcd;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.assertEquals;

/**
 * Placeholder instrumented test to verify the Android test framework is configured correctly.
 * These tests run on an Android device or emulator.
 */
@RunWith(AndroidJUnit4.class)
public class PlaceholderInstrumentedTest {

    @Test
    public void useAppContext() {
        Context appContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals("com.golfcart.gcd", appContext.getPackageName());
    }
}
