package com.zerolab.checkin.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zerolab.checkin.R
import com.zerolab.checkin.util.NetGeo

/**
 * v1.3.11：联网增强独立开关页（设置页「联网增强」入口进入）。
 * 三级联动：联网总开关 → 定位增强 → API Key，与设置页原内嵌逻辑一致，默认全关。
 */
class NetGeoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_geo)
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "联网增强"

        val prefs = getSharedPreferences(NetGeo.PREFS, MODE_PRIVATE)
        val swNet = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_net)
        val swGeo = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.sw_geo)
        val panelGeo = findViewById<View>(R.id.panel_geo)
        val panelKey = findViewById<View>(R.id.panel_key)
        val etKey = findViewById<EditText>(R.id.et_api_key)

        fun refresh() {
            val net = prefs.getBoolean(NetGeo.KEY_NET, false)
            val geo = prefs.getBoolean(NetGeo.KEY_GEO, false)
            swNet.isChecked = net
            swGeo.isChecked = geo
            panelGeo.visibility = if (net) View.VISIBLE else View.GONE
            panelKey.visibility = if (net && geo) View.VISIBLE else View.GONE
            etKey.setText(prefs.getString(NetGeo.KEY_API_KEY, "") ?: "")
        }
        refresh()

        swNet.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(NetGeo.KEY_NET, on).apply()
            if (!on) prefs.edit().putBoolean(NetGeo.KEY_GEO, false).apply()
            refresh()
        }
        swGeo.setOnCheckedChangeListener { _, on ->
            prefs.edit().putBoolean(NetGeo.KEY_GEO, on).apply()
            refresh()
        }
        etKey.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                prefs.edit().putString(NetGeo.KEY_API_KEY, s?.toString()?.trim() ?: "").apply()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        etKey.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) refresh() }
    }
}
