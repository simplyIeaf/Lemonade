package com.leaf.lemonade.lua

import java.util.TimeZone

class RandomV(seed: Long) {
    private var state = 0L
    private var inc = 0x69L

    init {
        nextU32()
        state += seed
        nextU32()
    }

    fun copy(): RandomV {
        val r = RandomV(0)
        r.state = state
        r.inc = inc
        return r
    }

    fun sameAs(o: RandomV) = state == o.state && inc == o.inc

    fun nextU32(): Int {
        val old = state
        state = old * 6364136223846793005L + inc
        val xorshifted = (((old ushr 18) xor old) ushr 27).toInt()
        return Integer.rotateRight(xorshifted, (old ushr 59).toInt())
    }

    private fun u32Long() = nextU32().toLong() and 0xFFFFFFFFL

    fun nextU64(): Long {
        val lo = u32Long()
        return (u32Long() shl 32) or lo
    }

    fun nextUnit(): Double = nextU64().toULong().toDouble() * Math.scalb(1.0, -64)

    fun nextRange(a: Double, b: Double): Double {
        val lo = if (a > b) b else a
        val hi = if (a > b) a else b
        val t = nextUnit()
        val r = lo * (1.0 - t) + hi * t
        return if (r < lo) lo else minOf(hi, r)
    }

    fun nextInt(min: Long, max: Long): Long {
        val span = max - min + 1
        if (span == 0L) return nextU64()
        if (span > 0 && span <= (1L shl 32)) return min + ((u32Long() * span) ushr 32)
        val hi = java.math.BigInteger(java.lang.Long.toUnsignedString(nextU64()))
            .multiply(java.math.BigInteger(java.lang.Long.toUnsignedString(span))).shiftRight(64)
        return min + hi.toLong()
    }

    companion object {
        private var counter = 0L

        fun fromEntropy(): RandomV {
            counter++
            return RandomV(System.nanoTime() xor (counter * -7046029254386353131L))
        }
    }
}

class DateTimeV(val millis: Long) : Comparable<DateTimeV> {
    class Civil(val year: Long, val month: Long, val day: Long, val hour: Long, val minute: Long, val second: Long, val milli: Long, val weekday: Long, val yday: Long)

    override fun compareTo(other: DateTimeV) = millis.compareTo(other.millis)
    override fun equals(other: Any?) = other is DateTimeV && other.millis == millis
    override fun hashCode() = millis.hashCode()
    override fun toString() = millis.toString()

    fun local(): DateTimeV = DateTimeV(millis + TimeZone.getDefault().getOffset(millis) / 1000L * 1000L)

    fun civil(): Civil {
        val days = Math.floorDiv(millis, 86_400_000L)
        val rem = Math.floorMod(millis, 86_400_000L)
        val (y, m, d) = civilFromDays(days)
        return Civil(y, m, d, rem / 3_600_000L, rem / 60_000L % 60, rem / 1000L % 60, rem % 1000L, Math.floorMod(days + 4, 7L), days - daysFromCivil(y, 1, 1) + 1)
    }

    fun iso(): String {
        val c = civil()
        val sb = StringBuilder(String.format("%04d-%02d-%02dT%02d:%02d:%02d", c.year, c.month, c.day, c.hour, c.minute, c.second))
        if (c.milli != 0L) sb.append(String.format(".%03d", c.milli))
        sb.append('Z')
        return sb.toString()
    }

    fun format(fmt: String): String {
        val c = civil()
        val h12 = if (c.hour % 12 == 0L) 12L else c.hour % 12
        val sb = StringBuilder()
        var i = 0
        while (i < fmt.length) {
            val ch = fmt[i]
            if (ch == '[') {
                val end = fmt.indexOf(']', i)
                if (end >= 0) { sb.append(fmt, i + 1, end); i = end + 1; continue }
            }
            val tok = TOKENS.firstOrNull { fmt.startsWith(it, i) }
            if (tok == null) { sb.append(ch); i++; continue }
            i += tok.length
            sb.append(when (tok) {
                "LT" -> format("h:mm A")
                "LTS" -> format("h:mm:ss A")
                "L" -> format("MM/DD/YYYY")
                "l" -> format("M/D/YYYY")
                "LL" -> format("MMMM D, YYYY")
                "ll" -> format("MMM D, YYYY")
                "LLL" -> format("MMMM D, YYYY h:mm A")
                "lll" -> format("MMM D, YYYY h:mm A")
                "LLLL" -> format("dddd, MMMM D, YYYY h:mm A")
                "llll" -> format("ddd, MMM D, YYYY h:mm A")
                "YYYY" -> String.format("%04d", c.year)
                "YY" -> String.format("%02d", Math.floorMod(c.year, 100L))
                "MMMM" -> MONTHS[(c.month - 1).toInt()]
                "MMM" -> MONTHS[(c.month - 1).toInt()].substring(0, 3)
                "MM" -> String.format("%02d", c.month)
                "M" -> c.month.toString()
                "DDDD" -> String.format("%03d", c.yday)
                "DDD" -> c.yday.toString()
                "DD" -> String.format("%02d", c.day)
                "D" -> c.day.toString()
                "dddd" -> DAYS[c.weekday.toInt()]
                "ddd" -> DAYS[c.weekday.toInt()].substring(0, 3)
                "dd" -> DAYS[c.weekday.toInt()].substring(0, 2)
                "d" -> c.weekday.toString()
                "HH" -> String.format("%02d", c.hour)
                "H" -> c.hour.toString()
                "hh" -> String.format("%02d", h12)
                "h" -> h12.toString()
                "mm" -> String.format("%02d", c.minute)
                "m" -> c.minute.toString()
                "ss" -> String.format("%02d", c.second)
                "s" -> c.second.toString()
                "SSS" -> String.format("%03d", c.milli)
                "SS" -> String.format("%02d", c.milli / 10)
                "S" -> (c.milli / 100).toString()
                "A" -> if (c.hour < 12) "AM" else "PM"
                else -> if (c.hour < 12) "am" else "pm"
            })
        }
        return sb.toString()
    }

    companion object {
        private val MONTHS = arrayOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")
        private val DAYS = arrayOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
        private val TOKENS = listOf("LLLL", "llll", "LLL", "lll", "LTS", "LL", "ll", "LT", "L", "l", "YYYY", "YY", "MMMM", "MMM", "MM", "M", "DDDD", "DDD", "DD", "D",
            "dddd", "ddd", "dd", "d", "HH", "H", "hh", "h", "mm", "m", "ss", "s", "SSS", "SS", "S", "A", "a")

        fun now() = DateTimeV(System.currentTimeMillis())

        fun daysFromCivil(y0: Long, m: Long, d: Long): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val mp = (m + 9) % 12
            val doy = (153 * mp + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146_097 + doe - 719_468
        }

        fun civilFromDays(z0: Long): Triple<Long, Long, Long> {
            val z = z0 + 719_468
            val era = (if (z >= 0) z else z - 146_096) / 146_097
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            return Triple(yoe + era * 400 + (if (m <= 2) 1 else 0), m, d)
        }

        private fun daysInMonth(y: Long, m: Long): Long = when (m) {
            4L, 6L, 9L, 11L -> 30
            2L -> if ((y % 4 == 0L && y % 100 != 0L) || y % 400 == 0L) 29 else 28
            else -> 31
        }

        fun fromUniversal(y: Long, mo: Long, d: Long, h: Long, mi: Long, s: Long, ms: Long): DateTimeV? {
            if (mo !in 1..12 || d < 1 || d > daysInMonth(y, mo)) return null
            return DateTimeV(daysFromCivil(y, mo, d) * 86_400_000L + h * 3_600_000L + mi * 60_000L + s * 1000L + ms)
        }

        fun parseIso(src: String): DateTimeV? {
            val s = src.trim()
            fun int(from: Int, to: Int): Long? {
                if (from < 0 || to > s.length || from >= to) return null
                val t = s.substring(from, to)
                if (!t.all { it in '0'..'9' }) return null
                return t.toLongOrNull()
            }
            if (s.length < 10 || s[4] != '-' || s[7] != '-') return null
            val y = int(0, 4) ?: return null
            val mo = int(5, 7) ?: return null
            val d = int(8, 10) ?: return null
            var h = 0L; var mi = 0L; var sec = 0L; var ms = 0L
            var i = 10
            if (i < s.length && (s[i] == 'T' || s[i] == 't' || s[i] == ' ')) {
                h = int(i + 1, i + 3) ?: return null
                if (s.getOrNull(i + 3) != ':') return null
                mi = int(i + 4, i + 6) ?: return null
                i += 6
                if (s.getOrNull(i) == ':') {
                    sec = int(i + 1, i + 3) ?: return null
                    i += 3
                    if (s.getOrNull(i) == '.' || s.getOrNull(i) == ',') {
                        val start = i + 1
                        var end = start
                        while (end < s.length && s[end] in '0'..'9') end++
                        if (end == start) return null
                        val frac = s.substring(start, minOf(end, start + 3))
                        ms = frac.toLong() * Math.pow(10.0, (3 - frac.length).toDouble()).toLong()
                        i = end
                    }
                }
            }
            var offset = 0L
            val c = s.getOrNull(i)
            when {
                c == null -> {}
                (c == 'Z' || c == 'z') && i + 1 == s.length -> {}
                c == '+' || c == '-' -> {
                    val oh = int(i + 1, i + 3) ?: return null
                    val rest = if (i + 3 <= s.length) s.substring(i + 3) else return null
                    val om = when {
                        rest.isEmpty() -> 0L
                        rest.length == 2 -> int(i + 3, i + 5) ?: return null
                        rest.length == 3 && rest.startsWith(":") -> int(i + 4, i + 6) ?: return null
                        else -> return null
                    }
                    offset = (oh * 60 + om) * (if (c == '+') 1 else -1)
                }
                else -> return null
            }
            if (h > 24 || mi > 59 || sec > 60) return null
            val dt = fromUniversal(y, mo, d, h, mi, sec, ms) ?: return null
            return DateTimeV(dt.millis - offset * 60_000L)
        }
    }
}
