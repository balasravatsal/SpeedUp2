package com.example.speedup.engine

import android.graphics.Rect

/**
 * Page-level context for disambiguating fields (phone vs country code, split vs full name).
 */
data class FormContext(
    val hasFirstName: Boolean = false,
    val hasLastName: Boolean = false,
    val hasMiddleName: Boolean = false,
    val hasFullName: Boolean = false,
    val hasCountryCode: Boolean = false,
    val hasPhone: Boolean = false,
    val phoneClusters: List<PhoneCluster> = emptyList()
) {
    val hasSplitName: Boolean get() = hasFirstName && hasLastName
    val hasSplitPhone: Boolean get() = hasCountryCode && hasPhone
}

data class PhoneCluster(
    val countryCodeBounds: Rect?,
    val phoneBounds: Rect?
)
