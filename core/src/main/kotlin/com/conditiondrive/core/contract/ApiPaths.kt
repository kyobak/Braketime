package com.conditiondrive.core.contract

object ApiPaths {
    const val HEALTHZ = "/healthz"
    const val DEVICES_VEHICLE = "/v1/devices/vehicle"
    const val PAIRING_CODES = "/v1/pairing/codes"
    const val PAIRING_CLAIM = "/v1/pairing/claim"
    const val PAIRING = "/v1/pairing"
    const val SUMMARY = "/v1/summary"
    const val SUMMARY_LATEST = "/v1/summary/latest"
    const val CONSENTS = "/v1/consents"              // /{type}
    const val TRIPS = "/v1/trips"
    const val ACCOUNT = "/v1/account"
    const val GUARDIANS = "/v1/guardians"            // /{id}, /{id}/confirm, /{id}/resend
    const val GUARDIAN_OPT_OUT = "/v1/guardian-opt-out"
    const val GUARDIAN_ALERTS = "/v1/guardian-alerts"
}
