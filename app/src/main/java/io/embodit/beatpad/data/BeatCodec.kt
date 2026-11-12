package io.embodit.beatpad.data

import io.embodit.beatpad.domain.beat.KitId
import io.embodit.beatpad.domain.beat.MY_SLOTS
import io.embodit.beatpad.domain.beat.Pattern
import io.embodit.beatpad.domain.beat.SavedBeat
import org.json.JSONArray
import org.json.JSONObject

/** files/beats.json: the 12「我的」slots + the beat currently on the ring (storage). */
object BeatCodec {
    fun encodeBeat(b: SavedBeat): JSONObject = JSONObject().apply {
        put("name", b.name); put("pattern", b.pattern.encode()); put("bpm", b.bpm); put("kit", b.kit.id)
        put("overrides", JSONObject().apply { b.overrides.forEach { (pad, path) -> put("p${pad + 1}", path) } })
    }

    fun decodeBeat(o: JSONObject): SavedBeat? {
        val p = Pattern.decode(o.optString("pattern")) ?: return null
        val ov = HashMap<Int, String>()
        o.optJSONObject("overrides")?.let { j -> j.keys().forEach { k -> Pattern.padIndex(k)?.let { ov[it] = j.getString(k) } } }
        return SavedBeat(o.optInt("name", 0), p, o.optInt("bpm", 96).coerceIn(60, 160), KitId.of(o.optString("kit")), ov)
    }

    fun encode(slots: List<SavedBeat?>, current: SavedBeat?): String = JSONObject().apply {
        put("version", 1)
        put("slots", JSONArray().apply { slots.forEach { put(it?.let { b -> encodeBeat(b) } ?: JSONObject.NULL) } })
        current?.let { put("current", encodeBeat(it)) }
    }.toString()

    fun decode(text: String): Pair<List<SavedBeat?>, SavedBeat?> {
        val o = JSONObject(text)
        val arr = o.optJSONArray("slots") ?: JSONArray()
        val slots = List(MY_SLOTS) { i -> if (i < arr.length() && !arr.isNull(i)) decodeBeat(arr.getJSONObject(i)) else null }
        val cur = o.optJSONObject("current")?.let { decodeBeat(it) }
        return slots to cur
    }
}
