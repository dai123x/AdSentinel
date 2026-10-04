package cloud.daixuan.adsentinel.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import cloud.daixuan.adsentinel.R
import cloud.daixuan.adsentinel.data.BlockStats
import cloud.daixuan.adsentinel.data.autoSkip
import cloud.daixuan.adsentinel.data.blockNotify
import cloud.daixuan.adsentinel.data.shakeGuard
import cloud.daixuan.adsentinel.engine.EngineBox
import cloud.daixuan.adsentinel.engine.RulesRepository
import cloud.daixuan.adsentinel.service.SkipAdService

class MainActivity : AppCompatActivity() {

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var btnEnable: MaterialButton
    private lateinit var rulesVersion: TextView
    private lateinit var statsToday: TextView
    private lateinit var statsTotal: TextView
    private lateinit var aboutText: TextView

    private val importRuleFile =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri != null) importRules(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        statusDot = findViewById(R.id.statusDot)
        statusText = findViewById(R.id.statusText)
        btnEnable = findViewById(R.id.btnEnable)
        rulesVersion = findViewById(R.id.rulesVersion)
        statsToday = findViewById(R.id.statsToday)
        statsTotal = findViewById(R.id.statsTotal)
        aboutText = findViewById(R.id.aboutText)

        btnEnable.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        findViewById<SwitchMaterial>(R.id.swAutoSkip).apply {
            isChecked = applicationContext.autoSkip
            setOnCheckedChangeListener { _, checked -> applicationContext.autoSkip = checked }
        }
        findViewById<SwitchMaterial>(R.id.swShakeGuard).apply {
            isChecked = applicationContext.shakeGuard
            setOnCheckedChangeListener { _, checked -> applicationContext.shakeGuard = checked }
        }
        findViewById<SwitchMaterial>(R.id.swNotify).apply {
            isChecked = applicationContext.blockNotify
            setOnCheckedChangeListener { _, checked -> applicationContext.blockNotify = checked }
        }

        findViewById<MaterialButton>(R.id.btnWhitelist).setOnClickListener {
            startActivity(Intent(this, WhitelistActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnImport).setOnClickListener {
            importRuleFile.launch(arrayOf("application/json", "text/plain", "*/*"))
        }
        findViewById<MaterialButton>(R.id.btnReset).setOnClickListener {
            RulesRepository.clearCustom(applicationContext)
            Toast.makeText(this, R.string.rules_reset_ok, Toast.LENGTH_SHORT).show()
            refresh()
        }

        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        aboutText.text = getString(R.string.about_line, version)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val enabled = isServiceEnabled()
        statusDot.setBackgroundResource(if (enabled) R.drawable.dot_on else R.drawable.dot_off)
        statusText.setText(if (enabled) R.string.service_running else R.string.service_stopped)
        btnEnable.visibility = if (enabled) View.GONE else View.VISIBLE

        val engine = EngineBox.get(applicationContext)
        rulesVersion.text = getString(R.string.rules_version, engine.builtinName, engine.ruleCount)

        statsToday.text = getString(R.string.stats_today, BlockStats.today(applicationContext))
        statsTotal.text = getString(R.string.stats_total, BlockStats.total(applicationContext))
    }

    private fun isServiceEnabled(): Boolean {
        val expected = "$packageName/${SkipAdService::class.java.name}"
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun importRules(uri: Uri) {
        try {
            val json = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                it.readText()
            } ?: throw IllegalArgumentException("无法读取所选文件")
            val count = RulesRepository.import(applicationContext, json)
            Toast.makeText(this, getString(R.string.rules_import_ok, count), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.rules_import_fail, e.message ?: "未知错误"), Toast.LENGTH_LONG)
                .show()
        }
        refresh()
    }
}
