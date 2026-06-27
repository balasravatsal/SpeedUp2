package com.example.speedup.engine

import org.junit.Assert.assertEquals
import org.junit.Test

class FieldMapperPhoneDisambiguationTest {

    @Test
    fun countryCodeLabel_mapsToCountryCode_notPhone() {
        val match = FieldMapper.matchField(
            viewId = "country_code",
            hint = "",
            contentDesc = "",
            labelText = "Country Code",
            inputType = 0,
            packageName = "com.test"
        )
        assertEquals(CanonicalField.COUNTRY_CODE, match.field)
    }

    @Test
    fun phoneNumberLabel_mapsToPhone_notCountryCode() {
        val match = FieldMapper.matchField(
            viewId = "phone",
            hint = "",
            contentDesc = "",
            labelText = "Phone Number",
            inputType = 0,
            packageName = "com.test"
        )
        assertEquals(CanonicalField.PHONE, match.field)
    }

    @Test
    fun familyName_mapsToLastName_notFullName() {
        val match = FieldMapper.matchField(
            viewId = "family_name",
            hint = "",
            contentDesc = "",
            labelText = "Family Name",
            inputType = 0,
            packageName = "com.test"
        )
        assertEquals(CanonicalField.LAST_NAME, match.field)
    }

    @Test
    fun surname_mapsToLastName() {
        val match = FieldMapper.matchField(
            viewId = "",
            hint = "",
            contentDesc = "",
            labelText = "Surname",
            inputType = 0,
            packageName = "com.test"
        )
        assertEquals(CanonicalField.LAST_NAME, match.field)
    }

    @Test
    fun splitNameContext_downgradesFullName() {
        val context = FormContext(hasFirstName = true, hasLastName = true)
        val fullNameMatch = FieldMatch(CanonicalField.FULL_NAME, 0.9f, 2, "alias")
        val refined = FormContextMapper.refineSingle(fullNameMatch, "Full Name", context, null)
        assertEquals(CanonicalField.UNKNOWN, refined.field)
    }

    @Test
    fun splitPhoneContext_countryCodeWinsOverPhone() {
        val context = FormContext(hasCountryCode = true, hasPhone = true)
        val phoneMatch = FieldMatch(CanonicalField.PHONE, 0.9f, 2, "alias")
        val refined = FormContextMapper.refineSingle(phoneMatch, "Country Code", context, null)
        assertEquals(CanonicalField.COUNTRY_CODE, refined.field)
    }
}
