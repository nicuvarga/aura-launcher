package tv.aura.launcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.concurrent.thread

class AppEntry(
    val pkg: String,
    val label: String,
    val intent: Intent,
    val art: Drawable?,
    val isBanner: Boolean,
    val synthetic: Boolean = false
)

sealed class Item
object HeaderItem : Item()
class TitleItem(val text: String) : Item()
class AppItem(val app: AppEntry, val fav: Boolean) : Item()

class MainActivity : Activity() {

    companion object {
        const val COLS = 5
        const val T_HEADER = 0
        const val T_TITLE = 1
        const val T_FAV = 2
        const val T_APP = 3
        const val SETTINGS_ID = "aura.settings"
        const val WALLPAPER_ID = "aura.wallpaper"
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        val DEFAULT_FAVS = listOf(
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.plexapp.android",
            "org.xbmc.kodi",
            "com.disney.disneyplus",
            "com.amazon.amazonvideo.livingroom",
            "com.amazon.amazonvideo.livingroom.nvidia",
            "com.spotify.tv.android"
        )
    }

    private lateinit var rv: RecyclerView
    private lateinit var bg: BackgroundView
    private val adapter = AppsAdapter()
    private var apps: List<AppEntry> = emptyList()
    private var items: List<Item> = emptyList()
    private var lastPkg: String? = null
    private var cardW = 0
    private var cardH = 0
    private val prefs by lazy { getSharedPreferences("aura", Context.MODE_PRIVATE) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            loadApps()
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val side = dp(44)
        val gap = dp(9)
        cardW = (resources.displayMetrics.widthPixels - 2 * side) / COLS - 2 * gap
        cardH = cardW * 9 / 16

        val root = FrameLayout(this)
        bg = BackgroundView(this)
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))

        rv = RecyclerView(this)
        rv.clipChildren = false
        rv.clipToPadding = false
        rv.setPadding(side, 0, side, dp(40))
        rv.overScrollMode = View.OVER_SCROLL_NEVER
        rv.itemAnimator = null
        val glm = GridLayoutManager(this, COLS)
        glm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                val t = adapter.getItemViewType(position)
                return if (t == T_HEADER || t == T_TITLE) COLS else 1
            }
        }
        rv.layoutManager = glm
        rv.addItemDecoration(GlassDockDecoration())
        rv.adapter = adapter
        root.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)

        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_PACKAGE_ADDED)
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED)
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED)
        filter.addDataScheme("package")
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        loadApps()
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        bg.reload(wallpaperDir(), prefs.getString("bg", "auto") ?: "auto")
        bg.start()
    }

    override fun onStop() {
        bg.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        goHome()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        goHome()
    }

    private fun goHome() {
        if (items.size < 2) return
        rv.scrollToPosition(0)
        focusPosition(1)
    }

    private fun focusPosition(pos: Int) {
        rv.post {
            val v = rv.layoutManager?.findViewByPosition(pos)
            v?.requestFocus()
        }
    }

    // ---------- data ----------

    private fun loadApps() {
        thread {
            val list = queryApps()
            runOnUiThread {
                apps = list
                rebuild()
            }
        }
    }

    private fun queryApps(): List<AppEntry> {
        val pm = packageManager
        val map = LinkedHashMap<String, AppEntry>()
        val cats = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
        for (cat in cats) {
            val q = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(q, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == packageName || map.containsKey(ai.packageName)) continue
                val banner: Drawable? = try {
                    ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm)
                } catch (e: Exception) {
                    null
                }
                val art: Drawable? = banner ?: try {
                    ri.loadIcon(pm)
                } catch (e: Exception) {
                    null
                }
                val launch = Intent(Intent.ACTION_MAIN)
                    .addCategory(cat)
                    .setClassName(ai.packageName, ai.name)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                map[ai.packageName] = AppEntry(
                    ai.packageName,
                    ri.loadLabel(pm).toString(),
                    launch,
                    art,
                    banner != null
                )
            }
        }
        val settingsIntent = Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val settingsIcon: Drawable? = try {
            val ri = pm.resolveActivity(settingsIntent, 0)
            ri?.loadIcon(pm)
        } catch (e: Exception) {
            null
        }
        map[SETTINGS_ID] = AppEntry(SETTINGS_ID, "Setări", settingsIntent, settingsIcon, false, true)
        map[WALLPAPER_ID] = AppEntry(
            WALLPAPER_ID, "Fundal", Intent(), getDrawable(android.R.drawable.ic_menu_gallery), false, true
        )
        return map.values.toList()
    }

    private fun favorites(): MutableList<String> {
        val saved = prefs.getString("favs", null)
        if (saved != null) {
            return saved.split("|").filter { it.isNotEmpty() }.toMutableList()
        }
        val installed = apps.map { it.pkg }.toSet()
        val favs = DEFAULT_FAVS.filter { it in installed }.toMutableList()
        for (a in apps.sortedBy { it.label.lowercase() }) {
            if (favs.size >= COLS - 1) break
            if (a.pkg !in favs && !a.synthetic) favs.add(a.pkg)
        }
        favs.add(SETTINGS_ID)
        saveFavorites(favs)
        return favs
    }

    private fun saveFavorites(favs: List<String>) {
        prefs.edit().putString("favs", favs.joinToString("|")).apply()
    }

    private fun rebuild() {
        if (apps.isEmpty()) return
        val favIds = favorites()
        val byPkg = apps.associateBy { it.pkg }
        val favs = favIds.mapNotNull { byPkg[it] }
        val favSet = favIds.toSet()
        val rest = apps.filter { it.pkg !in favSet }.sortedBy { it.label.lowercase() }

        val list = ArrayList<Item>()
        list.add(HeaderItem)
        for (a in favs) list.add(AppItem(a, true))
        if (rest.isNotEmpty()) {
            list.add(TitleItem("Toate aplicațiile"))
            for (a in rest) list.add(AppItem(a, false))
        }
        items = list
        adapter.notifyDataSetChanged()

        var idx = items.indexOfFirst { it is AppItem && it.app.pkg == lastPkg }
        if (idx < 0) idx = 1
        if (idx <= COLS) rv.scrollToPosition(0) else rv.scrollToPosition(idx)
        focusPosition(idx)
    }

    // ---------- actions ----------

    private fun wallpaperDir(): java.io.File? = getExternalFilesDir("wallpapers")

    private fun showWallpaperDialog() {
        val names = arrayOf("Automat (pozele și video-urile mele)", "Aurora", "Apus", "Ocean", "Noapte")
        val modes = arrayOf("auto", "aurora", "apus", "ocean", "noapte")
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Fundal")
            .setItems(names) { _, which ->
                val mode = modes[which]
                prefs.edit().putString("bg", mode).apply()
                bg.reload(wallpaperDir(), mode)
                bg.start()
                if (mode == "auto" && !bg.hasMedia()) {
                    Toast.makeText(
                        this,
                        "Nu am găsit poze sau video. Pune-le în: " + wallpaperDir()?.absolutePath,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .show()
    }

    private fun launch(app: AppEntry, from: View) {
        if (app.pkg == WALLPAPER_ID) {
            showWallpaperDialog()
            return
        }
        try {
            val opts = ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.width, from.height)
            startActivity(app.intent, opts.toBundle())
        } catch (e: Exception) {
            Toast.makeText(this, "Nu pot porni ${app.label}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showMenu(app: AppEntry) {
        val favs = favorites()
        val isFav = app.pkg in favs
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()

        if (isFav) {
            val i = favs.indexOf(app.pkg)
            if (i > 0) {
                labels.add("Mută la stânga")
                actions.add {
                    favs.removeAt(i)
                    favs.add(i - 1, app.pkg)
                    saveFavorites(favs)
                    rebuild()
                }
            }
            if (i < favs.size - 1) {
                labels.add("Mută la dreapta")
                actions.add {
                    favs.removeAt(i)
                    favs.add(i + 1, app.pkg)
                    saveFavorites(favs)
                    rebuild()
                }
            }
            labels.add("Scoate din dock")
            actions.add {
                favs.remove(app.pkg)
                saveFavorites(favs)
                rebuild()
            }
        } else {
            labels.add("Adaugă în dock")
            actions.add {
                favs.add(app.pkg)
                saveFavorites(favs)
                rebuild()
            }
        }
        if (!app.synthetic) {
            labels.add("Informații aplicație")
            actions.add {
                startSafely(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + app.pkg))
                )
            }
            labels.add("Dezinstalează")
            actions.add {
                startSafely(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + app.pkg)))
            }
        }

        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(app.label)
            .setItems(labels.toTypedArray()) { _, which -> actions[which].invoke() }
            .show()
    }

    private fun startSafely(i: Intent) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(this, "Acțiune indisponibilă", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- UI ----------

    private fun tileBackground(pkg: String): Drawable {
        val hue = ((pkg.hashCode() % 360) + 360) % 360
        val c1 = Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.50f, 0.46f))
        val c2 = Color.HSVToColor(floatArrayOf(((hue + 40) % 360).toFloat(), 0.60f, 0.22f))
        return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(c1, c2))
    }

    inner class AppVH(val rootView: LinearLayout, val art: ImageView, val label: TextView) :
        RecyclerView.ViewHolder(rootView)

    inner class PlainVH(v: View) : RecyclerView.ViewHolder(v)

    inner class AppsAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int {
            return when (val x = items[position]) {
                is HeaderItem -> T_HEADER
                is TitleItem -> T_TITLE
                is AppItem -> if (x.fav) T_FAV else T_APP
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val ctx = parent.context
            if (viewType == T_HEADER) {
                val box = LinearLayout(ctx)
                box.orientation = LinearLayout.VERTICAL
                box.setPadding(dp(9), dp(30), 0, dp(34))
                box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                val clock = TextClock(ctx)
                clock.format24Hour = "HH:mm"
                clock.format12Hour = "HH:mm"
                clock.textSize = 58f
                clock.setTextColor(Color.WHITE)
                clock.typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
                box.addView(clock)
                val date = TextClock(ctx)
                date.format24Hour = "EEEE, d MMMM"
                date.format12Hour = "EEEE, d MMMM"
                date.textSize = 15f
                date.setTextColor(Color.argb(190, 255, 255, 255))
                box.addView(date)
                return PlainVH(box)
            }
            if (viewType == T_TITLE) {
                val tv = TextView(ctx)
                tv.textSize = 13f
                tv.setTextColor(Color.argb(150, 255, 255, 255))
                tv.letterSpacing = 0.08f
                tv.setPadding(dp(9), dp(34), 0, dp(6))
                tv.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
                return PlainVH(tv)
            }

            val radius = dp(14).toFloat()
            val art = ImageView(ctx)
            art.layoutParams = LinearLayout.LayoutParams(cardW, cardH)
            art.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            art.clipToOutline = true

            val ring = GradientDrawable()
            ring.cornerRadius = radius
            ring.setColor(Color.TRANSPARENT)
            ring.setStroke(dp(2), Color.argb(235, 255, 255, 255))

            val label = TextView(ctx)
            label.textSize = 12f
            label.setTextColor(Color.WHITE)
            label.gravity = Gravity.CENTER
            label.maxLines = 1
            label.ellipsize = TextUtils.TruncateAt.END
            label.alpha = 0f
            label.setPadding(0, dp(12), 0, 0)
            label.layoutParams = LinearLayout.LayoutParams(cardW, WRAP)

            val box = LinearLayout(ctx)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            box.isFocusable = true
            box.clipChildren = false
            box.clipToPadding = false
            box.setPadding(0, dp(9), 0, dp(4))
            box.layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            box.addView(art)
            box.addView(label)

            val vh = AppVH(box, art, label)

            box.setOnFocusChangeListener { _, has ->
                val s = if (has) 1.13f else 1f
                box.translationZ = if (has) 1f else 0f
                art.foreground = if (has) ring else null
                art.animate()
                    .scaleX(s).scaleY(s)
                    .translationZ(if (has) dp(18).toFloat() else 0f)
                    .setDuration(230)
                    .setInterpolator(if (has) OvershootInterpolator(1.6f) else DecelerateInterpolator())
                    .start()
                label.animate()
                    .alpha(if (has) 1f else 0f)
                    .translationY(if (has) dp(5).toFloat() else 0f)
                    .setDuration(200)
                    .start()
                if (has) {
                    val pos = vh.bindingAdapterPosition
                    if (pos >= 0 && pos < items.size) {
                        val it = items[pos]
                        if (it is AppItem) lastPkg = it.app.pkg
                        if (pos <= COLS) rv.smoothScrollToPosition(0)
                    }
                }
            }
            box.setOnClickListener {
                val app = appAt(vh.bindingAdapterPosition)
                if (app != null) launch(app, art)
            }
            box.setOnLongClickListener {
                val app = appAt(vh.bindingAdapterPosition)
                if (app != null) showMenu(app)
                true
            }
            box.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_UP) {
                    val app = appAt(vh.bindingAdapterPosition)
                    if (app != null) showMenu(app)
                    true
                } else {
                    false
                }
            }
            return vh
        }

        private fun appAt(pos: Int): AppEntry? {
            if (pos < 0 || pos >= items.size) return null
            val it = items[pos]
            return if (it is AppItem) it.app else null
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (item is TitleItem) {
                (holder.itemView as TextView).text = item.text.uppercase()
                return
            }
            if (item !is AppItem || holder !is AppVH) return
            val app = item.app
            holder.label.text = app.label
            if (app.isBanner) {
                holder.art.scaleType = ImageView.ScaleType.CENTER_CROP
                holder.art.setPadding(0, 0, 0, 0)
                holder.art.background = null
            } else {
                val pad = cardH / 5
                holder.art.scaleType = ImageView.ScaleType.FIT_CENTER
                holder.art.setPadding(pad, pad, pad, pad)
                holder.art.background = tileBackground(app.pkg)
            }
            holder.art.setImageDrawable(app.art)
            if (!holder.rootView.isFocused) {
                holder.art.animate().cancel()
                holder.label.animate().cancel()
                holder.art.scaleX = 1f
                holder.art.scaleY = 1f
                holder.art.translationZ = 0f
                holder.art.foreground = null
                holder.label.alpha = 0f
                holder.label.translationY = 0f
                holder.rootView.translationZ = 0f
            }
        }
    }

    /** Panou de "sticla" translucid desenat in spatele randului de favorite (dock). */
    inner class GlassDockDecoration : RecyclerView.ItemDecoration() {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(38, 255, 255, 255) }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 255, 255)
            style = Paint.Style.STROKE
            strokeWidth = dp(1).toFloat()
        }
        private val r = RectF()

        override fun onDraw(c: Canvas, parent: RecyclerView, state: RecyclerView.State) {
            var found = false
            var top = 0f
            var bottom = 0f
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val vh = parent.getChildViewHolder(child) ?: continue
                if (vh.itemViewType != T_FAV) continue
                val t = child.top + child.translationY
                val b = child.bottom + child.translationY
                if (!found) {
                    top = t
                    bottom = b
                    found = true
                } else {
                    if (t < top) top = t
                    if (b > bottom) bottom = b
                }
            }
            if (!found) return
            val inset = dp(14).toFloat()
            r.set(
                parent.paddingLeft - inset,
                top - dp(8),
                parent.width - parent.paddingRight + inset,
                bottom + dp(8)
            )
            val rad = dp(26).toFloat()
            c.drawRoundRect(r, rad, rad, fill)
            c.drawRoundRect(r, rad, rad, stroke)
        }
    }
}
