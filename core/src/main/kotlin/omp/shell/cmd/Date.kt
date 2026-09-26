package omp.shell.cmd

import omp.shell.exec.CommandSpec
import omp.shell.exec.ExecContext
import omp.shell.exec.FileCommand
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@CommandSpec(
    name = "date",
    synopsis = "[+FORMAT]",
    group = "system",
    notes = "FORMAT supports %Y %m %d %H %M %S %F %T %a %b %Z %s %j %e %I %p %N %u %U %W",
)
object Date : FileCommand() {
    override val valueSpec = ""

    override fun execute(ctx: ExecContext, flags: String, options: Map<String, String>, operands: List<String>): Int {
        val now = Date(ctx.services.wallClockMillis())
        val fmt = operands.firstOrNull()
        if (fmt == null || !fmt.startsWith("+")) {
            ctx.outLine(SimpleDateFormat("EEE MMM d HH:mm:ss zzz yyyy", Locale.US).format(now))
            return ExecContext.EXIT_OK
        }
        val zone = TimeZone.getTimeZone(ctx.services.timeZoneId())
        val pattern = strftime(fmt.drop(1), zone, now)
        ctx.outLine(SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.format(now))
        return ExecContext.EXIT_OK
    }

    /** `%N` is nanoseconds, which `SimpleDateFormat` cannot express, so it is filled in by hand. */
    private fun strftime(spec: String, zone: TimeZone, now: Date): String {
        val cal = Calendar.getInstance(zone, Locale.US).apply { time = now }
        val nanos = String.format(Locale.US, "%09d", (now.time % 1000) * 1_000_000)
        return buildString {
            var i = 0
            while (i < spec.length) {
                if (spec[i] != '%' || i + 1 >= spec.length) {
                    append(spec[i])
                    i++
                    continue
                }
                when (val c = spec[i + 1]) {
                    'Y' -> append(cal.get(Calendar.YEAR))
                    'm' -> append(cal.get(Calendar.MONTH) + 1)
                    'd' -> append(cal.get(Calendar.DAY_OF_MONTH))
                    'e' -> append(cal.get(Calendar.DAY_OF_MONTH))
                    'H' -> append(cal.get(Calendar.HOUR_OF_DAY))
                    'I' -> append(cal.get(Calendar.HOUR))
                    'M' -> append(cal.get(Calendar.MINUTE))
                    'S' -> append(cal.get(Calendar.SECOND))
                    'F' -> append(cal.get(Calendar.YEAR)).append('-')
                        .append((cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')).append('-')
                        .append(cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0'))
                    'T' -> append(cal.get(Calendar.HOUR_OF_DAY).toString().padStart(2, '0')).append(':')
                        .append(cal.get(Calendar.MINUTE).toString().padStart(2, '0')).append(':')
                        .append(cal.get(Calendar.SECOND).toString().padStart(2, '0'))
                    'a' -> append(DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK)])
                    'A' -> append(DAY_NAMES[cal.get(Calendar.DAY_OF_WEEK)].capitalize())
                    'b' -> append(MONTH_NAMES[cal.get(Calendar.MONTH)])
                    'B' -> append(MONTH_NAMES[cal.get(Calendar.MONTH)].capitalize())
                    'Z' -> append(zone.getDisplayName(zone.inDaylightTime(now), TimeZone.SHORT, Locale.US))
                    's' -> append(now.time / 1000)
                    'j' -> append(cal.get(Calendar.DAY_OF_YEAR).toString().padStart(3, '0'))
                    'u' -> append(if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7 else cal.get(Calendar.DAY_OF_WEEK))
                    'N' -> append(nanos)
                    '%' -> append('%')
                    else -> append('%').append(c)
                }
                i += 2
            }
        }
    }

    private val DAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTH_NAMES = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}
