package com.bod3.app.obd.testing

import com.bod3.app.obd.uds.SecurityAccessSpec

/**
 * A stateful simulated UDS module for actuator and coding tests: diagnostic sessions, seed/key
 * security access, IO control (2F), routines (31), reads (22) and writes (2E), with switches for
 * negative responses, response-pending and silence. Use [respond] as a [SimModule] responder.
 */
class SimUdsEcu(
    val dids: MutableMap<Int, List<Int>> = mutableMapOf(),
    val ioDids: Set<Int> = emptySet(),
    val routines: Set<Int> = emptySet(),
    val writableDids: Set<Int> = emptySet(),
    /** Security levels this module offers (request-seed level → how the key is computed). */
    val security: Map<Int, SecurityAccessSpec> = emptyMap(),
    var seed: List<Int> = listOf(0x12, 0x34, 0x56, 0x78),
    /** Security level needed before 2F / 31 / 2E (null = none). */
    var requiredLevel: Int? = null,
) {
    var session = 0x01
        private set
    var unlockedLevel: Int? = null
        private set
    private var seedLevel: Int? = null

    /** IO-controlled DIDs currently overridden by the tester, with their control state. */
    val activeOutputs = mutableMapOf<Int, List<Int>>()
    val runningRoutines = mutableSetOf<Int>()

    /** Every request received, in order. */
    val received = mutableListOf<List<Int>>()

    /** Service → NRC: answer that service with `7F <service> <nrc>` (only for the first N times when [nrcTimes] > 0). */
    val nrcFor = mutableMapOf<Int, Int>()
    var nrcTimes = 0

    /** Services that first answer `7F <service> 78` and then the real reply. */
    val pendingFirst = mutableSetOf<Int>()

    /** Services that only ever answer `7F <service> 78` (result never arrives). */
    val pendingOnly = mutableSetOf<Int>()

    /** Services to which the module stays silent. */
    val silentFor = mutableSetOf<Int>()

    /** When set, an accepted 2E stores this instead of the requested data (to test read-back verification). */
    var corruptWrite: ((List<Int>) -> List<Int>)? = null

    private var nrcCount = 0

    fun respond(request: List<Int>): List<List<Int>> {
        received += request
        val service = request[0]
        if (service in silentFor) return emptyList()
        if (service in pendingOnly) return listOf(listOf(0x7F, service, 0x78))
        nrcFor[service]?.let { nrc ->
            if (nrcTimes == 0 || nrcCount < nrcTimes) {
                nrcCount++
                return listOf(listOf(0x7F, service, nrc))
            }
        }
        val reply = handle(request)
        return if (service in pendingFirst) listOf(listOf(0x7F, service, 0x78), reply) else listOf(reply)
    }

    private fun negative(service: Int, nrc: Int) = listOf(0x7F, service, nrc)

    private fun handle(r: List<Int>): List<Int> {
        val service = r[0]
        fun needsExtendedAndSecurity(): List<Int>? = when {
            session != 0x03 -> negative(service, 0x7F)
            requiredLevel != null && unlockedLevel != requiredLevel -> negative(service, 0x33)
            else -> null
        }
        return when (service) {
            0x10 -> {
                if (r.size != 2) return negative(service, 0x13)
                val type = r[1] and 0x7F
                if (type !in setOf(0x01, 0x03)) return negative(service, 0x12)
                session = type
                unlockedLevel = null
                seedLevel = null
                if (type == 0x01) {
                    activeOutputs.clear()
                    runningRoutines.clear()
                }
                listOf(0x50, type, 0x00, 0x32, 0x01, 0xF4)
            }
            0x27 -> {
                val sub = r.getOrNull(1) ?: return negative(service, 0x13)
                if (session == 0x01) return negative(service, 0x7F)
                if (sub % 2 == 1) {
                    if (sub !in security) return negative(service, 0x12)
                    seedLevel = sub
                    listOf(0x67, sub) + if (unlockedLevel == sub) seed.map { 0 } else seed
                } else {
                    val level = sub - 1
                    if (seedLevel != level) return negative(service, 0x24)
                    seedLevel = null
                    val expected = security.getValue(level).keyFor(seed)
                    if (r.drop(2) != expected) return negative(service, 0x35)
                    unlockedLevel = level
                    listOf(0x67, sub)
                }
            }
            0x3E -> listOf(0x7E, r.getOrNull(1)?.and(0x7F) ?: 0)
            0x22 -> {
                val did = (r[1] shl 8) or r[2]
                val value = activeOutputs[did] ?: dids[did] ?: return negative(service, 0x31)
                listOf(0x62, r[1], r[2]) + value
            }
            0x2F -> {
                if (r.size < 4) return negative(service, 0x13)
                val did = (r[1] shl 8) or r[2]
                if (did !in ioDids) return negative(service, 0x31)
                needsExtendedAndSecurity()?.let { return it }
                if (r[3] == 0x00) activeOutputs.remove(did) else activeOutputs[did] = r.drop(4)
                listOf(0x6F, r[1], r[2], r[3]) + r.drop(4)
            }
            0x31 -> {
                if (r.size < 4) return negative(service, 0x13)
                val routine = (r[2] shl 8) or r[3]
                if (routine !in routines) return negative(service, 0x31)
                needsExtendedAndSecurity()?.let { return it }
                when (r[1]) {
                    0x01 -> { runningRoutines += routine; listOf(0x71, 0x01, r[2], r[3]) }
                    0x02 -> {
                        if (routine !in runningRoutines) return negative(service, 0x24)
                        runningRoutines -= routine
                        listOf(0x71, 0x02, r[2], r[3])
                    }
                    0x03 -> listOf(0x71, 0x03, r[2], r[3], if (routine in runningRoutines) 0x01 else 0x02)
                    else -> negative(service, 0x12)
                }
            }
            0x2E -> {
                if (r.size < 4) return negative(service, 0x13)
                val did = (r[1] shl 8) or r[2]
                if (did !in writableDids) return negative(service, 0x31)
                needsExtendedAndSecurity()?.let { return it }
                val data = r.drop(3)
                dids[did] = corruptWrite?.invoke(data) ?: data
                listOf(0x6E, r[1], r[2])
            }
            else -> negative(service, 0x11)
        }
    }
}
