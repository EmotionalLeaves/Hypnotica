package app.hypnotica.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The recipe editor.
 *
 * A recipe is plain text, so this is a plain text box with a Run button and an
 * error line. Running it registers the sim and opens it in the player, which is
 * the shortest possible path from an idea to something moving.
 */
@Composable
fun RecipeScreen(state: AppState) {
    val store = state.recipeStore
    var listVersion by remember { mutableIntStateOf(0) }
    val entries = remember(listVersion) { store?.list() ?: emptyList() }
    var name by remember { mutableStateOf(entries.firstOrNull()?.name ?: "my-recipe") }
    var source by remember { mutableStateOf(entries.firstOrNull()?.source ?: STARTER) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Tokens.Bg)
            .safeDrawingPadding()
            .padding(horizontal = 14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            // Back to where you came from: both routes into this screen are
            // from Settings ("Write a sim", and the Code tab's button), and the
            // Library has no route here at all.
            GlyphButton("‹") { state.screen = Screen.SETTINGS }
            Spacer(Modifier.width(10.dp))
            Text("Recipes", color = Tokens.TextPrimary, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            Text(
                "${entries.count { it.def != null }} loaded",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (store == null) {
            Text(
                "Recipe storage is not available on this device.",
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
            return@Column
        }

        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionLabel("Saved recipes")
            if (entries.isEmpty()) {
                Text(
                    "None yet. The starter below is a working recipe; press Run.",
                    color = Tokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                // A recipe that does not parse is still kept, but it is marked,
                // so it cannot quietly vanish from the Library.
                ChipRow(
                    entries.map { if (it.error != null) "\u26A0 " + it.name else it.name },
                    entries.indexOfFirst { it.name == name }
                ) { i ->
                    name = entries[i].name
                    source = entries[i].source
                    message = entries[i].error
                    messageIsError = entries[i].error != null
                }
            }

            SectionLabel("Name")
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Tokens.TextPrimary, fontSize = 14.sp),
                    cursorBrush = SolidColor(Tokens.Accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            SectionLabel("Lua")
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 200.dp, max = 260.dp)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .padding(10.dp)
            ) {
                BasicTextField(
                    value = source,
                    onValueChange = { source = it },
                    textStyle = TextStyle(
                        color = Tokens.TextPrimary,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    ),
                    cursorBrush = SolidColor(Tokens.Accent),
                    modifier = Modifier.fillMaxSize()
                )
            }

            message?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    color = if (messageIsError) Tokens.Danger else Tokens.Success,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BigButton("Run", Modifier.weight(1f), primary = true) {
                    val error = store.save(name, source)
                    listVersion++
                    if (error != null) {
                        message = error
                        messageIsError = true
                    } else {
                        // The store sanitises the name on the way in, so the
                        // lookup has to use the same one. Using the raw text
                        // meant any punctuation at all, or a name over 48
                        // characters, compiled and saved fine and then reported
                        // "Saved, but the recipe did not load".
                        val stored = store.safeName(name)
                        val def = store.list().firstOrNull { it.name == stored }?.def
                        if (def == null) {
                            message = "Saved, but the recipe did not load"
                            messageIsError = true
                        } else {
                            message = "Running ${def.title}"
                            messageIsError = false
                            state.load(def)
                            state.screen = Screen.PLAYER
                        }
                    }
                }
                BigButton("Save", Modifier.weight(1f)) {
                    val error = store.save(name, source)
                    listVersion++
                    message = error ?: "Saved"
                    messageIsError = error != null
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("New") {
                    name = "my-recipe"
                    source = STARTER
                    message = null
                }
                Pill("Delete") {
                    val gone = store.delete(name)
                    listVersion++
                    if (gone) {
                        // Otherwise the editor still holds the recipe just
                        // deleted, and the next Run quietly recreates it.
                        name = "my-recipe"
                        source = STARTER
                        message = "Deleted"
                        messageIsError = false
                    } else {
                        message = "No saved recipe by that name"
                        messageIsError = true
                    }
                }
            }

            SectionLabel("What a recipe can call")
            Text(
                API_HELP,
                color = Tokens.TextSecondary,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.height(30.dp))
        }
    }
}

private val STARTER = """
    meta = {
      id = "my-recipe", title = "My recipe", shelf = "math",
      family = "Recipes", blurb = "A circle of dots that breathes.",
      hero = "Restart", palette = "Aurora", seconds = 20
    }

    params = {
      { key = "dots", label = "Dots", type = "int",
        min = 3, max = 400, default = 60, card = "data" },
      { key = "speed", label = "Speed", type = "float",
        min = 0.1, max = 4, default = 1, card = "motion" }
    }

    function setup(s)
      s.t = 0
      s.beats = 0
    end

    function step(s, dt)
      s.t = s.t + dt * param("speed")
      if math.floor(s.t) > s.beats then
        s.beats = math.floor(s.t)
        emit("cross", 0.5, 0, 0.7)
      end
    end

    function payoff(s) return s.beats == 8 end

    function draw(s, alpha)
      local n = param("dots")
      local r = 0.55 + math.sin(s.t) * 0.2
      for i = 1, n do
        local a = i / n * math.pi * 2
        point(math.cos(a) * r, math.sin(a) * r, 0.012, palette(i / n))
      end
    end
""".trimIndent()

private val API_HELP = """
    meta      id, title, shelf, family, blurb, hero, palette,
              sound, seconds, laws, formats
    params    key, label, type (int/float/bool/choice),
              min, max, default, card, advanced, unit, log
    setup(s)  step(s, dt)  draw(s, alpha)
    payoff(s) on_input(s, kind, x, y)

    param(k)  choice(k)  law(k)  rng(a, b)  rng_int(a, b)
    aspect()  sim_time()  emit(kind, value, pan, strength)

    disc(x,y,r,c)        rect(x,y,hw,hh,c,rot)
    bar(x,baseY,hw,h,c)  line(x1,y1,x2,y2,w,c)
    ring(x,y,r,t,c,gap,gapAngle,alpha)
    glow(x,y,r,c,a)      point(x,y,size,c)
    palette(t)  hsv(h,s,v)  flash(amount)

    y is up, the view shows y in -1..1, x spans +/- aspect().
""".trimIndent()
