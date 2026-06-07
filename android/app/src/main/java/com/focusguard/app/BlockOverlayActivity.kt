package com.focusguard.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.focusguard.app.databinding.ActivityBlockOverlayBinding
import kotlin.random.Random

class BlockOverlayActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockOverlayBinding

    // Keep motivational prompts consistent with the browser extension
    private val motivationalQuotes = arrayOf(
        "Is this cheap 15-second escape really worth your dreams?",
        "Your focus is being monetized. Take back control of your mind.",
        "Stop consuming someone else's highlight reel. Go build your own life.",
        "You opened this app to do something else. What was it?",
        "Every short you watch is a trade: your potential in exchange for flashing lights.",
        "Break the loop. Step away.",
        "Your future self is watching you right now. Make them proud.",
        "Success is built on what you do when you are bored. Don't scroll.",
        "This feed is designed to keep you trapped. Escape now."
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockOverlayBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Select a random quote
        val randomQuote = motivationalQuotes[Random.nextInt(motivationalQuotes.size)]
        binding.tvMotivationalQuote.text = "\"$randomQuote\""

        setupClickListeners()
    }

    private fun setupClickListeners() {
        binding.btnOverlayGoBack.setOnClickListener {
            // Trigger a broadcast to FocusAccessibilityService to perform back actions
            val exitIntent = Intent("com.focusguard.ACTION_EXIT_FEED")
            sendBroadcast(exitIntent)

            // Safeguard: Force navigate to launcher home screen to completely break the loop
            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(homeIntent)

            // Close the overlay
            finish()
        }
    }

    override fun onBackPressed() {
        // Intercept back presses on this screen so the user cannot simply tap back 
        // to bypass the block screen and continue scrolling reels/shorts.
        // They must click "Go Back to Work".
    }
}
