package nl.jolanrensen.permanentproxy

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import nl.jolanrensen.permanentproxy.Constants.launchUrlOnPhone

class DonateActivity : ComponentActivity() {

    private fun getPaypalLink(amount: Double) = "https://paypal.me/jolanrensen/$amount"

    val thankYou = 0.99
    val bigThankYou = 1.99
    val biggerThankYou = 4.99
    val biggestThankYou = 9.99

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_donate)

        findViewById<TextView>(R.id.thank_you_price).text =
            getString(R.string._dollar, thankYou.toString())
        findViewById<android.view.View>(R.id.thank_you_card).setOnClickListener {
            launchUrlOnPhone(getPaypalLink(thankYou))
        }

        findViewById<TextView>(R.id.big_thank_you_price).text =
            getString(R.string._dollar, bigThankYou.toString())
        findViewById<android.view.View>(R.id.big_thank_you_card).setOnClickListener {
            launchUrlOnPhone(getPaypalLink(bigThankYou))
        }

        findViewById<TextView>(R.id.bigger_thank_you_price).text =
            getString(R.string._dollar, biggerThankYou.toString())
        findViewById<android.view.View>(R.id.bigger_thank_you_card).setOnClickListener {
            launchUrlOnPhone(getPaypalLink(biggerThankYou))
        }

        findViewById<TextView>(R.id.biggest_thank_you_price).text =
            getString(R.string._dollar, biggestThankYou.toString())
        findViewById<android.view.View>(R.id.biggest_thank_you_card).setOnClickListener {
            launchUrlOnPhone(getPaypalLink(biggestThankYou))
        }

        // Entry point to the attribution screen. The donation options above are the original
        // author's and are deliberately left untouched.
        findViewById<TextView>(R.id.donate_about).setOnClickListener {
            startActivity(Intent(this, AboutActivity::class.java))
        }
    }
}
