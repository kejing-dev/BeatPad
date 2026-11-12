package io.embodit.beatpad.domain.beat

/** One bar of 4/4 = 16 sixteenth steps (loopEngine.length); 8 pads p1…p8 (index 0…7). */
const val STEPS = 16
const val PADS = 8

/**
 * The loop: 8 pads × 16 steps, each cell 0 = empty, 1 = light, 2 = mid, 3 = heavy (loopEngine.velocity). Immutable;
 * every edit returns a new pattern, so the undo stack can hold snapshots and the audio thread can read one without locks.
 */
class Pattern private constructor(private val cells: IntArray) {
    constructor() : this(IntArray(PADS * STEPS))

    operator fun get(pad: Int, step: Int): Int = cells[pad * STEPS + step]

    fun with(pad: Int, step: Int, vel: Int): Pattern {
        val c = cells.copyOf(); c[pad * STEPS + step] = vel.coerceIn(0, 3); return Pattern(c)
    }

    /** Overdub rule: the louder of the two stays (loopEngine.overdub). */
    fun merge(pad: Int, step: Int, vel: Int): Pattern = if (vel > get(pad, step)) with(pad, step, vel) else this

    val isEmpty: Boolean get() = cells.all { it == 0 }
    val hitCount: Int get() = cells.count { it > 0 }
    fun padHasHits(pad: Int) = (0 until STEPS).any { get(pad, it) > 0 }
    fun steps(pad: Int): List<Int> = (0 until STEPS).filter { get(pad, it) > 0 }

    fun encode(): String = cells.joinToString("") { it.toString() }

    override fun equals(other: Any?) = other is Pattern && other.cells.contentEquals(cells)
    override fun hashCode() = cells.contentHashCode()
    override fun toString() = (0 until PADS).joinToString("|") { p -> (0 until STEPS).joinToString("") { s -> ".xmX"[get(p, s)].toString() } }

    companion object {
        val EMPTY = Pattern()
        fun decode(s: String): Pattern? {
            if (s.length != PADS * STEPS || s.any { it !in '0'..'3' }) return null
            return Pattern(IntArray(PADS * STEPS) { s[it] - '0' })
        }

        /** Groove notation (content.grooveNotation): X heavy, x light, . empty; pads not listed are empty. */
        fun fromSteps(steps: Map<String, String>): Pattern {
            var p = Pattern()
            for ((pad, row) in steps) {
                val i = padIndex(pad) ?: error("bad pad $pad")
                require(row.length == STEPS) { "row $pad has ${row.length} steps" }
                row.forEachIndexed { s, ch ->
                    val v = when (ch) { 'X' -> 3; 'x' -> 1; '.' -> 0; else -> error("bad step char $ch") }
                    if (v > 0) p = p.with(i, s, v)
                }
            }
            return p
        }

        fun padIndex(id: String): Int? = id.removePrefix("p").toIntOrNull()?.minus(1)?.takeIf { it in 0 until PADS }
    }
}

/** Velocity 0…1 (mixer gain) ↔ three stored levels (loopEngine.velocity: light / mid / heavy). */
object Velocity {
    fun level(v: Float): Int = when { v < 0.55f -> 1; v < 0.82f -> 2; else -> 3 }
    fun gain(level: Int): Float = when (level) { 1 -> 0.5f; 2 -> 0.75f; else -> 1.0f }
    const val TAP = 0.75f
    /** Stone diameter by level (3.4 / 3.9 / 4.4 cm); the hit area is always 4.4 cm. */
    fun stoneDiameter(level: Int): Float = when (level) { 1 -> 0.034f; 2 -> 0.039f; else -> 0.044f }
}

enum class KitId(val id: String) { ACOUSTIC("acoustic"), ELECTRONIC("electronic"), HAND("hand");
    fun next(): KitId = entries[(ordinal + 1) % entries.size]
    companion object { fun of(id: String?): KitId = entries.firstOrNull { it.id == id } ?: ACOUSTIC }
}

/** content.kits: pad names (zh, en) per kit; the same grid slots change sound (padMapNote). */
object Kits {
    private val names: Map<KitId, List<Pair<String, String>>> = mapOf(
        KitId.ACOUSTIC to listOf("闭镲" to "Closed hat", "军鼓" to "Snare", "底鼓" to "Kick", "落地嗵" to "Floor tom", "开镲" to "Open hat", "高嗵" to "High tom", "拍手" to "Clap", "叮叮镲" to "Ride"),
        KitId.ELECTRONIC to listOf("闭镲" to "Closed hat", "军鼓" to "Snare", "底鼓" to "Kick", "低嗵" to "Low tom", "开镲" to "Open hat", "高嗵" to "High tom", "拍手" to "Clap", "牛铃" to "Cowbell"),
        KitId.HAND to listOf("沙锤" to "Shaker", "卡洪高音" to "Cajón slap", "卡洪低音" to "Cajón bass", "康加低" to "Conga low", "铃鼓" to "Tambourine", "康加高" to "Conga high", "邦戈" to "Bongo", "牛铃" to "Cowbell"),
    )
    fun padName(kit: KitId, pad: Int, zh: Boolean): String = names.getValue(kit)[pad].let { if (zh) it.first else it.second }
    fun kitName(kit: KitId, zh: Boolean): String = when (kit) { KitId.ACOUSTIC -> if (zh) "原声" else "Acoustic"; KitId.ELECTRONIC -> if (zh) "电子" else "Electronic"; KitId.HAND -> if (zh) "手鼓" else "Hand drums" }
    /** audioEngine.chokeGroups: closed hat cuts the open hat (acoustic, electronic). */
    fun chokeGroup(kit: KitId, pad: Int): Int = if (kit != KitId.HAND && (pad == 0 || pad == 4)) 1 else 0
    fun sampleKey(kit: KitId, pad: Int, heavy: Boolean) = "${kit.id}_p${pad + 1}_${if (heavy) "h" else "l"}"
}

data class Groove(val id: String, val zh: String, val en: String, val tagZh: String, val tagEn: String, val bpm: Int, val kit: KitId, val pattern: Pattern)

/** content.grooves: six ready-made one-bar beats (steps written by this app; rhythms are common patterns). */
object Grooves {
    val all: List<Groove> = listOf(
        Groove("garage", "车库排练", "Garage Practice", "最稳的那一种", "The steady one", 100, KitId.ACOUSTIC,
            Pattern.fromSteps(mapOf("p1" to "X.x.X.x.X.x.X.x.", "p2" to "....X.......X...", "p3" to "X.......X.X.....", "p5" to "..............x."))),
        Groove("friday", "周五放克", "Friday Funk", "军鼓藏了小心思", "Sneaky snare", 96, KitId.ACOUSTIC,
            Pattern.fromSteps(mapOf("p1" to "xxXxxxXxxxXxxxXx", "p2" to "....X..x.x..X...", "p3" to "X..X......X..X.."))),
        Groove("corner", "街角说唱", "Street Corner", "点头就对了", "Just nod", 88, KitId.ACOUSTIC,
            Pattern.fromSteps(mapOf("p1" to "x.x.x.x.x.x.x.x.", "p2" to "....X.......X...", "p3" to "X......X..X.....", "p7" to "....x.......x..."))),
        Groove("dancefloor", "凌晨舞池", "2 a.m. Dancefloor", "底鼓四下不停", "Four on the floor", 122, KitId.ELECTRONIC,
            Pattern.fromSteps(mapOf("p3" to "X...X...X...X...", "p5" to "..x...x...x...x.", "p7" to "....X.......X..."))),
        Groove("beach", "海边派对", "Beach Party", "一听就想晃", "Instant sway", 95, KitId.ELECTRONIC,
            Pattern.fromSteps(mapOf("p3" to "X...X...X...X...", "p2" to "...X..X....X..X.", "p1" to "x.x.x.x.x.x.x.x."))),
        Groove("rainy", "雨天咖啡馆", "Rainy Café", "轻一点，慢慢来", "Soft and easy", 84, KitId.HAND,
            Pattern.fromSteps(mapOf("p3" to "X..XX..XX..XX..X", "p7" to "x..x..x...x..x..", "p1" to "x.x.x.x.x.x.x.x."))),
    )
    fun of(id: String): Groove? = all.firstOrNull { it.id == id }
}

/** A saved beat in one of the 12 「我的」slots (content.myBeats). Pad overrides map pad index → imported file path. */
data class SavedBeat(val name: Int, val pattern: Pattern, val bpm: Int, val kit: KitId, val overrides: Map<Int, String> = emptyMap())

const val MY_SLOTS = 12
const val UNDO_DEPTH = 30
const val BPM_MIN = 60
const val BPM_MAX = 160
const val BPM_DEFAULT = 96
