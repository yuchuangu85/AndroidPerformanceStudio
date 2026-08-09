@file:Suppress("MaxLineLength")

package com.androidperformancestudio.battery.model

import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer
import com.androidperformancestudio.contracts.DeviceLocalId

private val deviceIdentity = DeviceIdentityPseudonymizer()

internal fun batteryDeviceLocalId(rawSerialOrLocalId: String): DeviceLocalId = deviceIdentity.localId(rawSerialOrLocalId)
