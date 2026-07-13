package com.kompressorlink.app.connection

import android.bluetooth.le.ScanFilter
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.IntentSender
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import com.kompressorlink.app.telemetry.ble.KlUuids

// One-time CDM association (PLAN.md §6.1's sanctioned auto-connect path).
// API 33+ only: the S23 FE is API 34; older devices get a toast from
// MainActivity instead — the manual-connect fallback is explicitly not a
// Phase 4 deliverable (spec §4.4).
class CdmAssociator(
    private val activity: ComponentActivity,
    private val onAssociated: (String) -> Unit,
) {
    // Must be registered before the activity is RESUMED — construct this
    // object in MainActivity.onCreate.
    private val chooserLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { /* the MAC arrives via onAssociationCreated, not this result */ }

    @RequiresApi(33)
    fun associate() {
        val cdm = activity.getSystemService(CompanionDeviceManager::class.java)
        val scanFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid.fromString(KlUuids.SERVICE))
            .build()
        val deviceFilter = BluetoothLeDeviceFilter.Builder()
            .setScanFilter(scanFilter)
            .build()
        val request = AssociationRequest.Builder()
            .addDeviceFilter(deviceFilter)
            .setSingleDevice(true)
            .build()
        cdm.associate(request, activity.mainExecutor, object : CompanionDeviceManager.Callback() {
            override fun onAssociationPending(intentSender: IntentSender) {
                chooserLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
            }

            override fun onAssociationCreated(associationInfo: AssociationInfo) {
                val mac = associationInfo.deviceMacAddress?.toString()?.uppercase()
                if (mac == null) {
                    Log.w(TAG, "association created without a MAC address")
                    return
                }
                // Presence observation: after this, KlCompanionService gets
                // onDeviceAppeared/onDeviceDisappeared. The String overload
                // is the API 31+ path [Likely deprecated in favor of a
                // request-object API in newer SDKs; functional at 34/35].
                @Suppress("DEPRECATION")
                cdm.startObservingDevicePresence(mac)
                onAssociated(mac)
            }

            override fun onFailure(error: CharSequence?) {
                Log.w(TAG, "association failed: $error")
            }
        })
    }

    private companion object {
        const val TAG = "CdmAssociator"
    }
}
