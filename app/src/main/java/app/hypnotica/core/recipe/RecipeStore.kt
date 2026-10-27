package app.hypnotica.core.recipe

import app.hypnotica.core.sim.SimDef
import app.hypnotica.sims.SimRegistry
import java.io.File

/**
 * Where recipes live: one plain text file each, in the app's own folder.
 *
 * A recipe is a text file on purpose. It can be pasted out of a chat, mailed to
 * someone, or kept in a note, and it is small enough to read at a glance.
 */
class RecipeStore(private val dir: File) {

    data class Entry(val name: String, val source: String, val def: SimDef?, val error: String?)

    private var entries: List<Entry> = emptyList()

    fun list(): List<Entry> = entries

    fun load() {
        if (!dir.exists()) dir.mkdirs()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.sortedBy { it.name }
            ?: emptyList()
        val loaded = ArrayList<Entry>(files.size + SAMPLES.size)

        // Samples are written out on first run so there is always something to read.
        if (files.isEmpty()) {
            for ((name, source) in SAMPLES) {
                runCatching { File(dir, name + EXT).writeText(source) }
            }
        }

        val finalFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(EXT) }?.sortedBy { it.name }
            ?: emptyList()
        for (f in finalFiles) {
            val source = runCatching { f.readText() }.getOrNull() ?: continue
            val name = f.name.removeSuffix(EXT)
            val parsed = LuaRecipe.parse(source, name)
            loaded.add(
                Entry(
                    name = name,
                    source = source,
                    def = parsed.getOrNull()?.toSimDef(),
                    error = parsed.exceptionOrNull()?.message
                )
            )
        }
        entries = loaded
        SimRegistry.setRecipes(loaded.mapNotNull { it.def })
    }

    /** Save, re-parse and register. Returns the error message, or null on success. */
    fun save(name: String, source: String): String? {
        val safe = name.trim().ifEmpty { "untitled" }
            .replace(Regex("[^A-Za-z0-9 _-]"), "")
            .take(48)
        val parsed = LuaRecipe.parse(source, safe)
        val error = parsed.exceptionOrNull()?.message
        return try {
            if (!dir.exists()) dir.mkdirs()
            File(dir, safe + EXT).writeText(source)
            load()
            error
        } catch (e: Exception) {
            "Could not save: ${e.message}"
        }
    }

    fun delete(name: String) {
        runCatching { File(dir, name + EXT).delete() }
        load()
    }

    fun sourceFor(name: String): String? = entries.firstOrNull { it.name == name }?.source

    companion object {
        const val EXT = ".recipe"

        /**
         * Shipped examples, chosen to show the whole API in as few lines as
         * possible: one that draws, one that integrates, one that uses the laws.
         */
        val SAMPLES: List<Pair<String, String>> = listOf(
            "orbit" to """
                meta = {
                  id = "orbit", title = "Two-body orbit", shelf = "physics",
                  family = "Orbits, gravity & space",
                  blurb = "A planet falling around a star, written in twenty lines.",
                  hero = "New orbit", palette = "Ember", seconds = 26, laws = true,
                  formats = { "loop", "song" }
                }

                params = {
                  { key = "speed", label = "Launch speed", type = "float",
                    min = 0.3, max = 2.0, default = 1.05, card = "motion" },
                  { key = "distance", label = "Start distance", type = "float",
                    min = 0.2, max = 0.9, default = 0.55, card = "data" },
                  { key = "trail", label = "Trail", type = "int",
                    min = 50, max = 4000, default = 900, card = "look" }
                }

                function setup(s)
                  s.x = param("distance"); s.y = 0
                  s.vx = 0; s.vy = param("speed")
                  s.tx = {}; s.ty = {}; s.n = 0
                  s.laps = 0; s.was_above = true
                end

                function step(s, dt)
                  local g = law("gravity") * 0.06
                  local r = math.sqrt(s.x * s.x + s.y * s.y)
                  if r < 0.05 then r = 0.05 end
                  local a = -g / (r * r)
                  s.vx = s.vx + a * s.x / r * dt
                  s.vy = s.vy + a * s.y / r * dt
                  s.x = s.x + s.vx * dt
                  s.y = s.y + s.vy * dt

                  local keep = param("trail")
                  s.n = s.n + 1
                  s.tx[(s.n % keep) + 1] = s.x
                  s.ty[(s.n % keep) + 1] = s.y

                  local above = s.y >= 0
                  if above ~= s.was_above then
                    s.was_above = above
                    if above then s.laps = s.laps + 1 end
                    emit("cross", (r / 0.9), s.x, 0.8)
                  end
                end

                function payoff(s) return s.laps == 3 end

                function draw(s, alpha)
                  glow(0, 0, 0.22, palette(0.9), 0.6)
                  disc(0, 0, 0.07, palette(0.95))
                  local keep = param("trail")
                  for i = 1, keep do
                    local x = s.tx[i]
                    if x ~= nil then
                      point(x, s.ty[i], 0.005, palette(i / keep))
                    end
                  end
                  glow(s.x, s.y, 0.08, palette(0.3), 0.7)
                  disc(s.x, s.y, 0.028, palette(0.25))
                end
            """.trimIndent(),

            "wave" to """
                meta = {
                  id = "wave", title = "Standing wave", shelf = "math",
                  family = "Waves & fields",
                  blurb = "Two waves travelling in opposite directions, added together.",
                  hero = "New wave", palette = "Ocean", seconds = 20,
                  formats = { "loop", "song" }
                }

                params = {
                  { key = "modes", label = "Harmonic", type = "int",
                    min = 1, max = 12, default = 3, card = "data" },
                  { key = "speed", label = "Speed", type = "float",
                    min = 0.2, max = 6, default = 1.6, card = "motion" },
                  { key = "points", label = "Resolution", type = "int",
                    min = 20, max = 600, default = 220, card = "look" }
                }

                function setup(s)
                  s.t = 0
                  s.peaks = 0
                end

                function step(s, dt)
                  s.t = s.t + dt * param("speed")
                  if math.floor(s.t / math.pi) > s.peaks then
                    s.peaks = math.floor(s.t / math.pi)
                    emit("cross", 0.6, 0, 0.7)
                  end
                end

                function payoff(s) return s.peaks == 6 end

                function draw(s, alpha)
                  local w = aspect() * 0.95
                  local n = param("points")
                  local k = param("modes")
                  for i = 0, n do
                    local u = i / n
                    local x = -w + u * 2 * w
                    local y = math.sin(u * math.pi * k) * math.cos(s.t) * 0.7
                    point(x, y, 0.008, palette(math.abs(y) * 1.4))
                  end
                  line(-w, 0, w, 0, 0.002, palette(0.1), 0.4)
                end
            """.trimIndent(),

            "rain" to """
                meta = {
                  id = "rain", title = "Rain on a pond", shelf = "art",
                  family = "Generative art",
                  blurb = "Drops land, rings spread, rings fade.",
                  hero = "Make it rain", palette = "Ocean", seconds = 24, laws = true,
                  formats = { "loop", "clean and reveal" }
                }

                params = {
                  { key = "rate", label = "Drops", type = "float",
                    min = 0.5, max = 40, default = 7, card = "motion", unit = "/s" },
                  { key = "grow", label = "Ring speed", type = "float",
                    min = 0.05, max = 1.5, default = 0.42, card = "motion" },
                  { key = "life", label = "Ring life", type = "float",
                    min = 0.5, max = 8, default = 3, card = "look", unit = "s" }
                }

                function setup(s)
                  s.r = {}
                  s.carry = 0
                  s.count = 0
                end

                function step(s, dt)
                  s.carry = s.carry + dt * param("rate")
                  while s.carry >= 1 do
                    s.carry = s.carry - 1
                    s.count = s.count + 1
                    local slot = (s.count % 90) + 1
                    s.r[slot] = { x = rng(-aspect(), aspect()), y = rng(-1, 1), age = 0, tint = rng() }
                    emit("hit", s.r[slot].tint, s.r[slot].x, 0.7)
                  end
                  for i = 1, 90 do
                    local d = s.r[i]
                    if d ~= nil then d.age = d.age + dt end
                  end
                end

                function payoff(s) return s.count == 40 end

                function draw(s, alpha)
                  local life = param("life")
                  for i = 1, 90 do
                    local d = s.r[i]
                    if d ~= nil and d.age < life then
                      local f = d.age / life
                      local radius = d.age * param("grow")
                      ring(d.x, d.y, radius, 0.004, palette(d.tint), 0, 0, 1 - f)
                    end
                  end
                end
            """.trimIndent()
        )
    }
}
