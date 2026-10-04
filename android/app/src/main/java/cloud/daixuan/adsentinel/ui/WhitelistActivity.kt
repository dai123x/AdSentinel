package cloud.daixuan.adsentinel.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import cloud.daixuan.adsentinel.R
import cloud.daixuan.adsentinel.data.whitelist

/** 白名单:勾选的应用不执行任何拦截,数据只保存在本机。 */
class WhitelistActivity : AppCompatActivity() {

    private data class AppEntry(val name: String, val pkg: String)

    private class Adapter(
        val entries: List<AppEntry>,
        val checked: MutableSet<String>,
        private val onToggle: () -> Unit,
    ) : BaseAdapter() {

        override fun getCount(): Int = entries.size

        override fun getItem(position: Int): AppEntry = entries[position]

        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context)
                .inflate(R.layout.item_whitelist, parent, false)
            val entry = getItem(position)
            view.findViewById<TextView>(R.id.appName).text = entry.name
            view.findViewById<TextView>(R.id.appPackage).text = entry.pkg
            val check = view.findViewById<CheckBox>(R.id.checkWhitelist)
            check.setOnCheckedChangeListener(null)
            check.isChecked = entry.pkg in checked
            check.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) checked.add(entry.pkg) else checked.remove(entry.pkg)
                onToggle()
            }
            view.setOnClickListener { check.toggle() }
            return view
        }
    }

    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_whitelist)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val self = packageName
        val entries = loadLaunchableApps().filter { it.pkg != self }
        val checked = applicationContext.whitelist.toMutableSet()

        findViewById<ListView>(R.id.whitelistList).adapter =
            Adapter(entries, checked) { dirty = true }

        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.whitelist_empty, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onPause() {
        super.onPause()
        if (!dirty) return
        val adapter = (findViewById<ListView>(R.id.whitelistList).adapter as? Adapter) ?: return
        applicationContext.whitelist = adapter.checked.toSet()
        Toast.makeText(this, R.string.whitelist_saved, Toast.LENGTH_SHORT).show()
        dirty = false
    }

    private fun loadLaunchableApps(): List<AppEntry> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .mapNotNull { info ->
                val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                AppEntry(info.loadLabel(pm).toString(), pkg)
            }
            .distinctBy { it.pkg }
            .sortedBy { it.name.lowercase() }
    }
}
