package com.androidperformancestudio.network.model

import com.androidperformancestudio.contracts.DeviceIdentityPseudonymizer

private val deviceIdentity = DeviceIdentityPseudonymizer()

/** Returns the stable application-local identifier persisted in place of a raw ADB serial. */
public fun networkDeviceLocalId(rawSerial: String): String = deviceIdentity.localId(rawSerial).value
