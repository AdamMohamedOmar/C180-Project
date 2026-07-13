package com.kompressorlink.app.connection

import android.companion.AssociationInfo
import android.companion.CompanionDeviceService
import androidx.annotation.RequiresApi

// Receives CDM presence events (declared in the manifest with
// BIND_COMPANION_DEVICE_SERVICE). This is the cold-start entry point:
// ignition on -> ESP32 advertises -> onDeviceAppeared -> foreground
// ConnectionService, no manual app launch (spec §4.4, PLAN.md §6.1).
@RequiresApi(31)
class KlCompanionService : CompanionDeviceService() {

    // API 33+ overloads.
    override fun onDeviceAppeared(associationInfo: AssociationInfo) {
        ConnectionService.start(this, appeared = true)
    }

    override fun onDeviceDisappeared(associationInfo: AssociationInfo) {
        ConnectionService.start(this, appeared = false)
    }

    // API 31-32 String overloads (deprecated in 33, still dispatched there).
    @Deprecated("API 31-32 path")
    override fun onDeviceAppeared(address: String) {
        ConnectionService.start(this, appeared = true)
    }

    @Deprecated("API 31-32 path")
    override fun onDeviceDisappeared(address: String) {
        ConnectionService.start(this, appeared = false)
    }
}
