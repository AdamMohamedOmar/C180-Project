package com.kompressorlink.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Walking skeleton for the Robolectric stack added in Phase 4.5 Task 0.
// application = Application::class keeps the real KompressorLinkApp DI
// container (BLE, DataStore, WorkManager) out of every Robolectric test.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RobolectricSmokeTest {
    @Test
    fun `application context is available`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertNotNull(app)
    }
}
