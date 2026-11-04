package app.sada.poc

import android.content.Context
import org.json.JSONObject
import java.io.File

/** State shared between the web layer (WebActivity) and the audio service. */
object NativeApp {
    /** Shown in the player while the narration is being made (first time per episode). */
    @Volatile var preparing = ""
    @Volatile var error = ""

    private fun eventsFile(ctx: Context) = File(ctx.filesDir, "web-events.jsonl")

    /**
     * Questions and answers are queued here and handed to the web layer when it asks. The web page may be paused while
     * the screen is locked, so nothing is lost: it drains the queue when the app is opened again.
     */
    @Synchronized fun pushEvent(ctx: Context, o: JSONObject) {
        try { eventsFile(ctx).appendText(o.toString() + "\n") } catch (_: Exception) {}
    }

    @Synchronized fun drainEvents(ctx: Context): String {
        val f = eventsFile(ctx)
        if (!f.exists()) return "[]"
        val lines = try { f.readLines().filter { it.isNotBlank() } } catch (_: Exception) { emptyList() }
        f.delete()
        return "[" + lines.joinToString(",") + "]"
    }

    /** What the web player shows: the audio service's state plus preparation status. */
    fun stateJson(): String {
        val live = SadaService.live
        val o = if (live != null) JSONObject(live.stateJson()) else JSONObject()
            .put("running", false).put("loaded", false).put("playing", false).put("ended", false)
            .put("phase", "STOPPED").put("stateText", "").put("episodeId", "").put("positionMs", 0).put("durationMs", 0)
        o.put("preparing", preparing)
        o.put("error", error)
        return o.toString()
    }
}
