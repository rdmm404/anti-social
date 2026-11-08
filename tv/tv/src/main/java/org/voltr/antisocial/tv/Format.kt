package org.voltr.antisocial.tv

import java.text.DateFormat
import java.util.Date

/** A Unix time as the TV shows the time of day, in its own locale. */
fun timeOfDay(timestamp: Double): String =
    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date((timestamp * 1000).toLong()))
