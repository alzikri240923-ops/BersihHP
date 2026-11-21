package com.bersih.hp

import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale

class JunkCat(val name: String) {
    val files = mutableListOf<File>()
    var size = 0L
    var checked = true
    fun add(f: File) {
        files.add(f)
        if (f.isFile) size += f.length()
    }
}

class MainActivity : Activity() {

    private val bg = Color.parseColor("#0F172A")
    private val cardColor = Color.parseColor("#1E293B")
    private val accent = Color.parseColor("#22C55E")
    private val blue = Color.parseColor("#3B82F6")
    private val red = Color.parseColor("#EF4444")
    private val muted = Color.parseColor("#94A3B8")

    private lateinit var storageText: TextView
    private lateinit var storageBar: ProgressBar
    private lateinit var junkBox: LinearLayout
    private lateinit var junkTotal: TextView
    private lateinit var cleanBtn: Button
    private lateinit var appBox: LinearLayout
    private val cats = mutableListOf<JunkCat>()

    // ---------- Helper tampilan (Tahap 4) ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun fmt(b: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            b >= gb -> String.format(Locale.US, "%.2f GB", b / gb)
            b >= mb -> String.format(Locale.US, "%.1f MB", b / mb)
            b >= kb -> String.format(Locale.US, "%.0f KB", b / kb)
            else -> "$b B"
        }
    }

    private fun round(color: Int, r: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(r).toFloat()
    }

    private fun tv(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = sp
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(cardColor)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit) = Button(this).apply {
        text = label
        setTextColor(Color.WHITE)
        isAllCaps = false
        textSize = 15f
        background = round(color, 14)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(10) }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- Layar utama ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(28), dp(16), dp(36))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(root)
        })

        root.addView(tv("Bersih HP", 30f, Color.WHITE, true))
        root.addView(tv("Bersihkan sampah dan cache dengan aman", 14f, muted))

        // TAHAP 1: info penyimpanan
        val c1 = cardBox()
        c1.addView(tv("Penyimpanan", 17f, Color.WHITE, true))
        storageText = tv("", 14f, muted).apply { setPadding(0, dp(6), 0, dp(10)) }
        c1.addView(storageText)
        storageBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(accent)
            layoutParams = LinearLayout.LayoutParams(-1, dp(10))
        }
        c1.addView(storageBar)
        c1.addView(button("Bersihkan cache aplikasi ini", blue) { cleanOwnCache() })
        root.addView(c1)

        // TAHAP 2: file sampah
        val c2 = cardBox()
        c2.addView(tv("File Sampah", 17f, Color.WHITE, true))
        c2.addView(tv("Memindai file .tmp, .log, .bak, thumbnail, APK sisa, dan folder kosong.", 13f, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        c2.addView(button("Pindai sampah", accent) { scan() })
        junkBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        junkTotal = tv("", 14f, accent, true).apply { setPadding(0, dp(8), 0, 0) }
        c2.addView(junkBox)
        cleanBtn = button("Bersihkan yang dipilih", red) { confirmClean() }
        cleanBtn.visibility = View.GONE
        c2.addView(cleanBtn)
        root.addView(c2)

        // TAHAP 3: cache per aplikasi
        val c3 = cardBox()
        c3.addView(tv("Cache Aplikasi", 17f, Color.WHITE, true))
        c3.addView(tv("Android tidak mengizinkan aplikasi menghapus cache aplikasi lain. Ketuk aplikasi, pilih Penyimpanan, lalu Hapus cache.", 13f, muted).apply {
            setPadding(0, dp(4), 0, 0)
        })
        c3.addView(button("Muat daftar cache aplikasi", blue) { loadApps() })
        appBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        c3.addView(appBox)
        root.addView(c3)

        refreshStorage()
    }

    override fun onResume() {
        super.onResume()
        refreshStorage()
    }

    private fun refreshStorage() {
        val st = StatFs(Environment.getDataDirectory().path)
        val total = st.blockCountLong * st.blockSizeLong
        val free = st.availableBytes
        val used = total - free
        storageText.text = "Terpakai ${fmt(used)} dari ${fmt(total)}\nKosong ${fmt(free)}"
        storageBar.progress = if (total > 0) (used * 100 / total).toInt() else 0
    }

    private fun cleanOwnCache() {
        var n = 0L
        listOfNotNull(cacheDir, externalCacheDir).forEach { d ->
            n += d.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            d.listFiles()?.forEach { it.deleteRecursively() }
        }
        toast("Cache aplikasi ini dibersihkan: ${fmt(n)}")
        refreshStorage()
    }

    // ---------- TAHAP 2: pemindai dan penghapus ----------

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askStorageAccess() {
        toast("Izinkan akses semua file, lalu kembali dan tekan Pindai lagi")
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(
                arrayOf(
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), 1
            )
        }
    }

    private fun scan() {
        if (!hasStorageAccess()) {
            askStorageAccess()
            return
        }
        junkBox.removeAllViews()
        junkBox.addView(tv("Memindai...", 14f, muted))
        cleanBtn.visibility = View.GONE
        Thread {
            val result = doScan()
            runOnUiThread { showJunk(result) }
        }.start()
    }

    private fun doScan(): List<JunkCat> {
        val tmp = JunkCat("File sementara (.tmp, .temp)")
        val log = JunkCat("File log (.log)")
        val bak = JunkCat("File cadangan lama (.bak, .old)")
        val thumb = JunkCat("Thumbnail galeri")
        val apk = JunkCat("APK sisa di folder Download")
        val empty = JunkCat("Folder kosong")
        val root = Environment.getExternalStorageDirectory()
        try {
            root.walkTopDown()
                .onEnter { d -> !(d.parentFile == root && d.name == "Android") }
                .forEach { f ->
                    if (f.isDirectory) {
                        if (f != root && f.parentFile != root && f.list()?.isEmpty() == true) empty.add(f)
                    } else {
                        val p = f.path.lowercase(Locale.ROOT)
                        val ext = f.extension.lowercase(Locale.ROOT)
                        when {
                            "/.thumbnails/" in p || "/.thumbnail/" in p -> thumb.add(f)
                            ext == "tmp" || ext == "temp" -> tmp.add(f)
                            ext == "log" -> log.add(f)
                            ext == "bak" || ext == "old" -> bak.add(f)
                            ext == "apk" && "/download" in p -> apk.add(f)
                        }
                    }
                }
        } catch (e: Exception) {
            // abaikan folder yang tidak bisa dibaca
        }
        return listOf(tmp, log, bak, thumb, apk, empty).filter { it.files.isNotEmpty() }
    }

    private fun showJunk(list: List<JunkCat>) {
        junkBox.removeAllViews()
        cats.clear()
        cats.addAll(list)
        if (list.isEmpty()) {
            junkBox.addView(tv("Tidak ada sampah ditemukan. HP kamu sudah bersih.", 14f, accent))
            return
        }
        junkBox.addView(junkTotal)
        for (c in list) {
            val cb = CheckBox(this).apply {
                text = "${c.name}\n${c.files.size} item \u2022 ${fmt(c.size)}"
                setTextColor(Color.WHITE)
                textSize = 14f
                isChecked = true
                buttonTintList = ColorStateList.valueOf(accent)
                setPadding(dp(8), dp(8), 0, dp(8))
                setOnCheckedChangeListener { _, v ->
                    c.checked = v
                    updateTotal()
                }
            }
            junkBox.addView(cb)
        }
        updateTotal()
        cleanBtn.visibility = View.VISIBLE
    }

    private fun updateTotal() {
        junkTotal.text = "Dipilih: ${fmt(cats.filter { it.checked }.sumOf { it.size })}"
    }

    private fun confirmClean() {
        val sel = cats.filter { it.checked }
        if (sel.isEmpty()) {
            toast("Pilih minimal satu kategori")
            return
        }
        val total = sel.sumOf { it.size }
        AlertDialog.Builder(this)
            .setTitle("Hapus sampah?")
            .setMessage("${fmt(total)} akan dihapus permanen dan tidak bisa dikembalikan.")
            .setPositiveButton("Hapus") { _, _ -> doClean(sel) }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun doClean(sel: List<JunkCat>) {
        junkBox.removeAllViews()
        junkBox.addView(tv("Menghapus...", 14f, muted))
        cleanBtn.visibility = View.GONE
        Thread {
            var freed = 0L
            for (c in sel) {
                for (f in c.files) {
                    val len = if (f.isFile) f.length() else 0L
                    if (f.delete()) freed += len
                }
            }
            runOnUiThread {
                toast("Selesai! ${fmt(freed)} dibersihkan")
                refreshStorage()
                scan()
            }
        }.start()
    }

    // ---------- TAHAP 3: cache per aplikasi ----------

    private fun hasUsageAccess(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun loadApps() {
        if (!hasUsageAccess()) {
            toast("Izinkan 'Akses penggunaan' untuk Bersih HP, lalu kembali dan tekan Muat lagi")
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return
        }
        appBox.removeAllViews()
        appBox.addView(tv("Memuat...", 14f, muted))
        Thread {
            val pm = packageManager
            val ssm = getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val pkgs = pm.queryIntentActivities(launcher, 0)
                .map { it.activityInfo.packageName }
                .distinct()
                .filter { it != packageName }
            val rows = mutableListOf<Triple<String, String, Long>>()
            for (pkg in pkgs) {
                try {
                    val st = ssm.queryStatsForPackage(StorageManager.UUID_DEFAULT, pkg, Process.myUserHandle())
                    val name = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                    rows.add(Triple(pkg, name, st.cacheBytes))
                } catch (e: Exception) {
                    // lewati aplikasi yang tidak bisa dibaca
                }
            }
            rows.sortByDescending { it.third }
            runOnUiThread { showApps(rows) }
        }.start()
    }

    private fun showApps(rows: List<Triple<String, String, Long>>) {
        appBox.removeAllViews()
        val total = rows.sumOf { it.third }
        appBox.addView(tv("Total cache aplikasi: ${fmt(total)}", 14f, accent, true).apply {
            setPadding(0, dp(10), 0, dp(4))
        })
        for ((pkg, name, size) in rows.filter { it.third > 0 }.take(30)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                }
            }
            row.addView(tv(name, 14f, Color.WHITE).apply {
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            })
            row.addView(tv(fmt(size), 14f, muted))
            appBox.addView(row)
        }
    }
}
