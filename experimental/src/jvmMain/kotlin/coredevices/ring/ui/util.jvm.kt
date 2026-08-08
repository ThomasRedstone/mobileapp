package coredevices.ring.ui

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale

actual fun isLocale24HourFormat(): Boolean {
    val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)
        ?.toPattern() ?: return true
    return !pattern.contains('a')
}
