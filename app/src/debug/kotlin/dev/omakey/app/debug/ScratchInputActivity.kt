package dev.omakey.app.debug

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * debug-only scratch surface for manually verifying the IME against a handful of EditorInfo
 * variants without depending on a third-party app being present on a fresh emulator image.
 * Not included in release builds.
 */
class ScratchInputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        fun addField(label: String, inputType: Int) {
            container.addView(TextView(this).apply { text = label })
            container.addView(
                EditText(this).apply {
                    this.inputType = inputType
                    hint = label
                },
            )
        }

        addField("Plain text (multiline)", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        addField("Number", InputType.TYPE_CLASS_NUMBER)
        addField("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        addField("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        addField("URL", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)

        setContentView(ScrollView(this).apply { addView(container) })
    }
}
