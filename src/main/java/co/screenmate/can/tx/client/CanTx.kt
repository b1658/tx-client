package co.screenmate.can.tx.client

import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Privileged CAN **transmit** — the SDK's TX transport plus the one validated write path: the
 * **exact TACC set-speed scroll** (steering-wheel right scroll, `VCLEFT_switchStatus` 0x3C2).
 *
 * ## Why a root executor
 * TX needs uid 0 (the `screenmate_car` binder is guarded by SCREENMATE_INTERNAL, which no app
 * process holds — see `docs/TX.md`), so it can't be called in-process. Instead we run the gated
 * root executor `co.screenmate.can.tx.SmCanTx` as uid 0 over the box's OWN loopback root adbd — the
 * same `dadb` channel the injector's `Patcher` uses. Nothing persistent is written: only a ~20 KB
 * dex staged to `/data/local/tmp`, and this library ships that dex as an asset so consumers merge it
 * in automatically rather than keeping their own copy in sync.
 *
 * ## The scroll path (validated on-car 2026-08-24)
 * The set-speed is moved by emulating the steering-wheel RIGHT scroll wheel — a driver INPUT the
 * car's own TACC logic accumulates, NOT a `DAS_control` override, so it is safe while moving and
 * needs no counter/checksum (the frame is byte-perfect). Two on-car realities make a naive "+N"
 * wrong, and [exactFrames] encodes both:
 *  1. a **±5** frame SNAPS to the next multiple of 5 in the scroll direction (it does NOT add 5), and
 *  2. two identical frames in a row **collapse** (read as a held wheel = one detent).
 * So [scrollExact] climbs with `+5` snaps separated by an absorbed `+1`, never repeating a `±5`.
 * This lands the set-speed 1:1 for the pressed delta. The **0x213 `UI_cruiseControl` changer path
 * was tried and abandoned** — the box is an *additive* origin and its frames are out-voted by the
 * MCU's authoritative stream at the gateway (see `docs/EXPLORATIONS.md`).
 *
 * ## The consumer boundary (deliberate)
 * This class does NOT read vehicle signals. [scrollExact] is given the *current* set-speed by the
 * caller because only a consumer reading the vendor broadcast (`:privileged-client` / the injector)
 * can see `DI_CRUISE_SET_SPEED`. That read + the user-facing arm switch are the consumer's job; the
 * grid math and the frame wire format are car knowledge and live here. Every safety gate (ack token
 * + deny-by-default allowlist + blocklist + live speed gate + audit log) lives in `SmCanTx`.
 */
class CanTx(private val ctx: Context) {

    /**
     * Move the TACC **set-speed** to `currentSetSpeed + delta` EXACTLY, emulating the right scroll.
     *
     * @param currentSetSpeed the live set-speed the caller just read (e.g. `DI_CRUISE_SET_SPEED`,
     *   rounded to an int). The exact frame plan depends on it because a ±5 detent snaps to a
     *   multiple of 5 rather than adding 5.
     * @param delta signed number of km/h (or mph — whatever the cluster shows) steps to change by.
     * @return the `[TX-AUDIT]` output, or `"no-op"` when [delta] is 0.
     */
    fun scrollExact(
        currentSetSpeed: Int,
        delta: Int,
        tickMs: Int = SCROLL_TICK_MS,
        interMs: Int = SCROLL_REST_MS,
    ): String {
        val frames = exactFrames(currentSetSpeed, delta)
        if (frames.isEmpty()) return "no-op"
        return "exact Δ=$delta from $currentSetSpeed → ${currentSetSpeed + delta} frames=$frames\n" +
            rightScrollSeq(frames, tickMs, interMs)
    }

    /**
     * The exact ±5 / ±1 right-scroll frame plan that moves the set-speed by [delta] from
     * [currentSetSpeed], honouring the two on-car rules (see the class KDoc): a ±5 SNAPS to the next
     * multiple of 5, and consecutive identical frames collapse. Pure and deterministic — verified
     * against a snap simulator for all realistic start/delta. Multiple-of-5 targets (speed limits)
     * come out clean `[5,1,5,…]`; a non-multiple-of-5 target has a `+1` detent tail.
     */
    fun exactFrames(currentSetSpeed: Int, delta: Int): List<Int> {
        val f = ArrayList<Int>()
        if (delta == 0) return f
        val t = currentSetSpeed + delta
        var cur = currentSetSpeed; var guard = 0
        if (delta > 0) {
            while (guard++ < 200) {
                var nm = (Math.floorDiv(cur, 5) + 1) * 5           // next multiple of 5 above cur
                if (nm > t) break
                if (f.isNotEmpty() && f.last() == 5) {             // separate consecutive +5s
                    if (cur + 1 > t) break
                    f.add(1); cur += 1; nm = (Math.floorDiv(cur, 5) + 1) * 5
                    if (nm > t) break
                }
                f.add(5); cur = nm
            }
            while (cur < t) { f.add(1); cur += 1 }
        } else {
            while (guard++ < 200) {
                var nm = Math.ceil(cur / 5.0).toInt() * 5 - 5      // next multiple of 5 below cur
                if (nm < t) break
                if (f.isNotEmpty() && f.last() == -5) {
                    if (cur - 1 < t) break
                    f.add(-1); cur -= 1; nm = Math.ceil(cur / 5.0).toInt() * 5 - 5
                    if (nm < t) break
                }
                f.add(-5); cur = nm
            }
            while (cur > t) { f.add(-1); cur -= 1 }
        }
        return f
    }

    /**
     * Fire an EXPLICIT right-scroll tick sequence — the low-level primitive behind [scrollExact],
     * mapping to `SmCanTx scroll-seq`. [steps] is the exact list of signed per-frame set-speed ticks
     * (each ±1..±5 lands; a `0` is an explicit rest frame), each held [tickMs] with [interMs] between
     * and a final rest to release the wheel. RELATIVE and speed-exempt (a driver input). Prefer
     * [scrollExact] unless you are hand-crafting a sequence. Returns the `[TX-AUDIT]` output.
     */
    fun rightScrollSeq(steps: List<Int>, tickMs: Int = SCROLL_TICK_MS, interMs: Int = SCROLL_REST_MS): String {
        if (steps.isEmpty()) return "no-op"
        val csv = steps.joinToString(",") { it.coerceIn(-31, 31).toString() }
        return run("scroll-seq", "0", "962", SCROLL_BASE, "3",
                   tickMs.coerceIn(0, 1000).toString(), interMs.coerceIn(0, 1000).toString(), csv)
    }

    /**
     * Fire an EXPLICIT LEFT-scroll (media **volume**) tick sequence — the same `VCLEFT_switchStatus`
     * 0x3C2 frame as [rightScrollSeq] but writing the LEFT field (byte 2, `swcLeftScrollTicks`)
     * instead of the right/set-speed field. RELATIVE and speed-exempt: it emulates the driver turning
     * the left thumbwheel, so the car's own audio logic applies it (all the way to the amp) and no
     * counter/checksum is needed (on-car verified: 0x3C2 has neither). [steps] is the signed per-frame
     * tick list (a `0` is an explicit rest; consecutive identical non-zero frames collapse as one
     * detent, so separate detents with a rest). Volume is RELATIVE with no CAN read-back, so the
     * caller tracks its own offset. Returns the `[TX-AUDIT]` output.
     */
    fun leftScrollSeq(steps: List<Int>, tickMs: Int = SCROLL_TICK_MS, interMs: Int = SCROLL_REST_MS): String {
        if (steps.isEmpty()) return "no-op"
        val csv = steps.joinToString(",") { it.coerceIn(-31, 31).toString() }
        return run("scroll-seq", "0", "962", SCROLL_BASE, "2",
                   tickMs.coerceIn(0, 1000).toString(), interMs.coerceIn(0, 1000).toString(), csv)
    }

    /**
     * Nudge the media **volume** by [detents] left-scroll steps (signed: + louder, − quieter), in a
     * single frame sequence. Each detent is one `±1` frame separated by an explicit rest so the car
     * counts them individually (identical adjacent frames collapse to one). No-op for 0. The exact
     * loudness change per detent is the car's (≈ one UI volume step); this is intentionally relative.
     */
    fun volumeDetents(detents: Int, tickMs: Int = SCROLL_TICK_MS, interMs: Int = SCROLL_REST_MS): String {
        if (detents == 0) return "no-op"
        val unit = if (detents > 0) 1 else -1
        val steps = ArrayList<Int>(kotlin.math.abs(detents) * 2)
        repeat(kotlin.math.abs(detents)) { steps.add(unit); steps.add(0) } // detent, then rest
        return leftScrollSeq(steps, tickMs, interMs)
    }

    /**
     * Run an `SmCanTx` command as root — the low-level escape hatch. The ack token is prepended here;
     * [args] is the command + its parameters, e.g. `run("scroll-seq", …)`. Returns the executor's
     * stdout (the `[TX-AUDIT]` lines), or an ERROR string. Every gate still applies in `SmCanTx`.
     */
    fun run(vararg args: String): String {
        return try {
            connect().use { d ->
                stageDex(d) ?: return "ERROR: staging smcan-tx.dex failed"
                val cmd = buildString {
                    append("CLASSPATH=").append(DEX_REMOTE)
                    append(" app_process /system/bin ").append(MAIN)
                    append(' ').append(ACK)
                    args.forEach { append(' ').append(shellQuote(it)) }
                }
                val r = d.shell(cmd)
                r.output.trim().ifEmpty { "(no output, exit=${r.exitCode})" }
            }
        } catch (t: Throwable) {
            "ERROR: ${t.javaClass.simpleName}: ${t.message}"
        }
    }

    /** True if the loopback root adbd is reachable + authorized (same requirement as `Patcher`). */
    fun canConnect(): Boolean = runCatching { connect().use { true } }.getOrDefault(false)

    // --- transport (mirrors Patcher's dadb setup; shares the persisted adb keypair) ---
    private fun connect(): Dadb = Dadb.create(HOST, PORT, keyPair())

    private fun keyPair(): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /** Copy the bundled dex to internal storage, then cp into /data/local/tmp (root-readable). */
    private fun stageDex(d: Dadb): String? {
        val local = File(ctx.filesDir, DEX_ASSET)
        runCatching {
            ctx.assets.open(DEX_ASSET).use { input -> local.outputStream().use { input.copyTo(it) } }
        }.onFailure { return null }
        if (!local.exists() || local.length() == 0L) return null
        runCatching { local.setReadable(true, false) }
        val r = d.shell("cp '${local.absolutePath}' '$DEX_REMOTE' && chmod 644 '$DEX_REMOTE' && echo OK")
        return if (r.output.contains("OK")) DEX_REMOTE else null
    }

    /** Minimal single-quote-safe wrapping for shell args (ints/flags in practice). */
    private fun shellQuote(s: String): String =
        if (s.matches(Regex("[A-Za-z0-9_./-]+"))) s else "'" + s.replace("'", "'\\''") + "'"

    private companion object {
        const val HOST = "127.0.0.1"
        const val PORT = 5555
        const val DEX_ASSET = "smcan-tx.dex"
        const val DEX_REMOTE = "/data/local/tmp/smcan-tx.dex"
        const val MAIN = "co.screenmate.can.tx.SmCanTx"
        const val ACK = "I_UNDERSTAND_TX_RISK"
        const val SCROLL_BASE = "2955000000000080" // 0x3C2 mux1 neutral frame; byte2=volume, byte3=set-speed
        const val SCROLL_TICK_MS = 100  // on-car validated hold per frame (ms) — 100 lands exactly
        const val SCROLL_REST_MS = 80   // on-car validated gap between frames (ms) — the edge the car counts
    }
}
