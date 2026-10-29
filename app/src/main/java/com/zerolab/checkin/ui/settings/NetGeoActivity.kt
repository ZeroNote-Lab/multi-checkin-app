package com.zerolab.checkin.ui.settings

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.zerolab.checkin.R
import com.zerolab.checkin.theme.ThemeUi
import com.zerolab.checkin.util.NetGeo

/**
 * 增强服务页：目前只有「联网增强」（位置地名解析）。
 * 页面名称保留「增强服务」，后台计步 / Health Connect 已回退。
 */
class NetGeoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_net_geo)
        // v1.3.14：全局主题换肤（根背景）
        ThemeUi.apply(this, findViewById(android.R.id.content))
        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<TextView>(R.id.tv_title).text = "增强服务"

        val prefs = getSharedPreferences(NetGeo.PREFS, MODE_PRIVATE)
        val swNet = findViewById<SwitchCompat>(R.id.sw_net)
        val swGeo = findViewById<SwitchCompat>(R.id.sw_geo)
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
