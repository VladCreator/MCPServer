package com.vlad.mcpserver.fixture

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity

/** Separate synthetic target: never included in the Tester APK. */
class FixtureActivity : ComponentActivity() {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mode = intent.getStringExtra("screen") ?: "cards"
        if (mode == "secure") window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(40), dp(16), dp(24))
        }
        fun label(text: String) = TextView(this).apply { this.text = text; textSize = 18f }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener { action() }
        }
        column.addView(label("Synthetic $mode fixture"))
        if (mode == "cards") {
            val status = label(savedInstanceState?.getString("status") ?:
                if (intent.data != null) "Deep link opened" else "No project opened").apply { id = R.id.status }
            column.addView(status)
            val actions = label(savedInstanceState?.getString("actions") ?: "0").apply { id = R.id.action_count }
            column.addView(actions)
            for ((name, containerId) in listOf("Alpha" to R.id.project_alpha, "Beta" to R.id.project_beta)) {
                column.addView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    id = containerId
                    contentDescription = "Project $name"
                    addView(label("Project $name"))
                    addView(EditText(this@FixtureActivity).apply {
                        id = R.id.field
                        setSingleLine()
                        setText("$name input")
                        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                    })
                    addView(button("Open") {
                        status.text = "Opened $name"
                        actions.text = (actions.text.toString().toInt() + 1).toString()
                    }.apply { id = R.id.open })
                })
            }
            column.addView(FrameLayout(this).apply {
                clipChildren = true
                addView(button("Partially clipped") { status.text = "Clipped clicked" }.apply {
                    id = R.id.clipped
                }, FrameLayout.LayoutParams(dp(240), dp(90)))
            }, LinearLayout.LayoutParams(dp(240), dp(30)))
            for (screen in listOf("compose", "webview", "secure")) {
                column.addView(button("Show $screen") {
                    startActivity(Intent(this, FixtureActivity::class.java).putExtra("screen", screen))
                })
            }
            column.addView(label("Unicode: café 日本語 😀"))
            column.addView(label("Scrollable synthetic footer"), LinearLayout.LayoutParams(-1, dp(800)))
            column.addView(label("End of synthetic list"))
        } else if (mode == "compose") {
            column.addView(ComposeView(this).apply {
                setContent {
                    var input by remember { mutableStateOf("Compose input") }
                    var clicked by remember { mutableStateOf(false) }
                    Column(Modifier.padding(16.dp)) {
                        Column(Modifier.semantics(mergeDescendants = true) {
                            contentDescription = "Merged project"
                        }.clickable { clicked = true }.padding(16.dp)) {
                            BasicText("Merged title")
                            BasicText("Merged detail")
                        }
                        BasicText(if (clicked) "Compose clicked" else "Compose ready")
                        BasicTextField(input, { input = it }, Modifier.semantics {
                            contentDescription = "Compose field"
                        })
                    }
                }
            })
        } else if (mode == "webview") {
            column.addView(WebView(this).apply {
                id = R.id.web
                settings.javaScriptEnabled = false
                loadDataWithBaseURL(null, "<html><body><h1>Local web fixture</h1>" +
                    "<p>Accessible web content</p><button>Web button</button></body></html>", "text/html", "UTF-8", null)
            }, LinearLayout.LayoutParams(-1, dp(260)))
        } else {
            column.addView(label("Protected synthetic content"))
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        findViewById<TextView>(R.id.status)?.let { outState.putString("status", it.text.toString()) }
        findViewById<TextView>(R.id.action_count)?.let { outState.putString("actions", it.text.toString()) }
        super.onSaveInstanceState(outState)
    }
}
