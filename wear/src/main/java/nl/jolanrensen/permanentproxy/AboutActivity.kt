package nl.jolanrensen.permanentproxy

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import nl.jolanrensen.permanentproxy.Constants.openUrl

/**
 * Attribution screen.
 *
 * The original app, its design and the donation options belong to Jolan Rensen (@Jolanrensen) and
 * are kept untouched. This fork's Shizuku edition is maintained by @Xiaoxiaoyu1321 and was
 * developed with assistance from DeepSeek Harness.
 */
class AboutActivity : ComponentActivity() {

    private companion object {
        const val URL_ORIGINAL_AUTHOR = "https://github.com/Jolanrensen"
        const val URL_MAINTAINER = "https://github.com/Xiaoxiaoyu1321"
        const val URL_SHIZUKU = "https://github.com/RikkaApps/Shizuku-API"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        findViewById<TextView>(R.id.about_original_author).setOnClickListener {
            openUrl(URL_ORIGINAL_AUTHOR)
        }
        findViewById<TextView>(R.id.about_maintainer).setOnClickListener {
            openUrl(URL_MAINTAINER)
        }
        findViewById<TextView>(R.id.about_shizuku).setOnClickListener {
            openUrl(URL_SHIZUKU)
        }
        findViewById<TextView>(R.id.about_close).setOnClickListener { finish() }
    }
}
