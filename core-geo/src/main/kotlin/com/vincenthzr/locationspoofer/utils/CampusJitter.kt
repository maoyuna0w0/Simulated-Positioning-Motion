package com.vincenthzr.locationspoofer.utils

import kotlin.math.cos

/**
 * 时间确定性 GPS 抖动。
 */
class CampusJitter(
    seed: Long,
    private val amplitudeM: Double = CampusRules.JITTER_AMPLITUDE_M,
    private val maxM: Double = CampusRules.JITTER_MAX_M
) {

    // 两轴用不同盐值，避免 x/y 同步波动成一条斜线
    private val noiseX = MotionRealism.SmoothNoise(seed xor 0x51ED_2703L, CampusRules.JITTER_MIN_PERIOD_SEC, CampusRules.JITTER_MAX_PERIOD_SEC)
    private val noiseY = MotionRealism.SmoothNoise(seed xor 0x7A31_9C45L, CampusRules.JITTER_MIN_PERIOD_SEC, CampusRules.JITTER_MAX_PERIOD_SEC)

    // 【修复点】原项目缺失 MotionRealism.JitterNoise，改用本文件内自定义的 JitterNoise
    private val measX = JitterNoise(seed xor 0x3B1F_9A62L, CampusRules.JITTER_MEASUREMENT_STD_M)
    private val measY = JitterNoise(seed xor 0x6C42_D7B1L, CampusRules.JITTER_MEASUREMENT_STD_M)

    fun offsetAt(epochMs: Long, dtScale: Double = 1.0): DoubleArray {
        val t = epochMs / 1000.0
        // 慢漂 + 秒级测量噪声；两分量同样按 dtScale 收缩
        var x = (amplitudeM * noiseX.value(t) + measX.value(epochMs)) * dtScale
        var y = (amplitudeM * noiseY.value(t) + measY.value(epochMs)) * dtScale
        val limit = (maxM * dtScale).coerceAtLeast(MIN_MAX_M)
        val len = kotlin.math.sqrt(x * x + y * y)
        if (len > limit && len > 0.0) {
            val k = limit / len
            x *= k
            y *= k
        }
        return doubleArrayOf(x, y)
    }

    fun apply(lat: Double, lng: Double, epochMs: Long, dtScale: Double = 1.0): Pair<Double, Double> {
        val (east, north) = offsetAt(epochMs, dtScale).let { it[0] to it[1] }
        val dLat = north / METERS_PER_DEG_LAT
        val dLng = east / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
        return Pair(lat + dLat, lng + dLng)
    }

    companion object {
        const val METERS_PER_DEG_LAT = 111_132.0
        const val MIN_MAX_M = 0.15

        fun plain(seed: Long): CampusJitter =
            CampusJitter(seed, CampusRules.PLAIN_JITTER_AMPLITUDE_M, CampusRules.PLAIN_JITTER_MAX_M)

        fun campus(seed: Long): CampusJitter = CampusJitter(seed)
    }
    
    // 【新增】替代丢失的类，提供一个基于时间的确定性伪随机噪声
    private class JitterNoise(private val seed: Long, private val stdDev: Double) {
        fun value(epochMs: Long): Double {
            var h = epochMs xor seed
            h = h * 0x9E3779B97F4A7C15UL.toLong()
            h = h xor (h ushr 33)
            val uniform = ((h and 0xFFFF) / 65535.0) * 2.0 - 1.0
            return uniform * stdDev
        }
    }
}
