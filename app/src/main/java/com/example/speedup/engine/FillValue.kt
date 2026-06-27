package com.example.speedup.engine

sealed class FillValue {
    data class Text(val value: String) : FillValue()
    data class Option(val matchTokens: List<String>) : FillValue()
    data class RangeYears(val years: Double) : FillValue()
    data class BooleanValue(val checked: Boolean) : FillValue()
    data class DateValue(val isoDate: String) : FillValue()
    data class FileUri(val contentUri: String) : FillValue()

    fun displayText(): String = when (this) {
        is Text -> value
        is Option -> matchTokens.firstOrNull().orEmpty()
        is RangeYears -> years.toString()
        is BooleanValue -> if (checked) "Yes" else "No"
        is DateValue -> isoDate
        is FileUri -> contentUri
    }
}
