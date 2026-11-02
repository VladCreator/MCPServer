package com.vlad.mcpserver.fixture

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.Gravity
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
        render(savedInstanceState)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.data != null) render(null)
    }

    private fun render(savedInstanceState: Bundle?) {
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
            for (screen in listOf("compose", "webview", "secure", "reliability")) {
                column.addView(button("Show $screen") {
                    startActivity(Intent(this, FixtureActivity::class.java).putExtra("screen", screen))
                }.apply { if (screen == "compose") id = R.id.show_compose })
            }
            column.addView(label("Unicode: café 日本語 😀"))
            column.addView(label("Scrollable synthetic footer"), LinearLayout.LayoutParams(-1, dp(800)))
            column.addView(label("End of synthetic list"))
        } else if (mode == "reliability") {
            for (screen in listOf("search", "drawer", "delayed", "dynamic", "viewer")) {
                column.addView(button("Show $screen") {
                    startActivity(Intent(this, FixtureActivity::class.java).putExtra("screen", screen))
                })
            }
        } else if (mode == "search") {
            val status = label("Search results").apply { id = R.id.status }
            column.addView(status)
            column.addView(FrameLayout(this).apply {
                addView(button("Cancel") { status.text = "My projects" }.apply { id = R.id.search_cancel },
                    FrameLayout.LayoutParams(dp(100), dp(60), Gravity.END))
            }, LinearLayout.LayoutParams(-1, dp(60)))
            column.addView(button("Disabled Cancel") { status.text = "Invalid disabled action" }.apply {
                id = R.id.disabled_cancel
                isEnabled = false
            })
        } else if (mode == "drawer") {
            fun list(drawer: Boolean) = ScrollView(this).apply {
                id = if (drawer) R.id.drawer else R.id.home_scroll
                setBackgroundColor(if (drawer) 0xFFEEEEEE.toInt() else 0xFFFFFFFF.toInt())
                addView(LinearLayout(this@FixtureActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    for (i in 0 until 30) addView(label(if (drawer) "Drawer item $i" else "Home item $i").apply {
                        if (i == 4) id = if (drawer) R.id.drawer_anchor else R.id.home_anchor
                    }, LinearLayout.LayoutParams(-1, dp(60)))
                })
            }
            column.addView(FrameLayout(this).apply {
                addView(list(false), FrameLayout.LayoutParams(-1, -1))
                addView(list(true), FrameLayout.LayoutParams(dp(200), -1, Gravity.START))
            }, LinearLayout.LayoutParams(-1, dp(420)))
        } else if (mode == "delayed") {
            val empty = FrameLayout(this)
            setContentView(empty)
            empty.addView(button("Empty then populate") {
                empty.removeAllViews()
                empty.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        empty.addView(label("Delayed readable content").apply { id = R.id.status })
                    }
                }, 300)
            }, FrameLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(60) })
            return
        } else if (mode == "dynamic") {
            column.addView(label("Log Information").apply { id = R.id.custom_title })
            val shortcut = button("Submittals") {}.apply { id = R.id.shortcut }
            column.addView(shortcut)
            column.addView(button("Refresh shortcuts") { shortcut.text = "Team members" })
        } else if (mode == "viewer") {
            column.addView(label("Synthetic viewer").apply { id = R.id.status })
            column.addView(button("View Properties") {
                Dialog(this).apply {
                    setContentView(label("Synthetic properties"))
                    show()
                }
            })
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
