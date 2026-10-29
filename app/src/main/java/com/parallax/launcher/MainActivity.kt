package com.parallax.launcher

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val apps = mutableStateOf<List<AppInfo>>(emptyList())
    private val userName = mutableStateOf<String?>(null)

    // Bumped whenever Home or Back is pressed while we're already showing, so the grid jumps to the top.
    private val homePresses = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        readName(intent)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                homePresses.intValue++
            }
        })
        setContent {
            HomeScreen(
                apps = apps.value,
                userName = userName.value,
                homePresses = homePresses.intValue,
                onLaunch = ::launch,
                onMove = { from, to ->
                    apps.value = apps.value.toMutableList().apply { add(to, removeAt(from)) }
                },
                onMoveFinished = { AppOrder.save(this, apps.value) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            apps.value = AppOrder.apply(this@MainActivity, AppRepository.load(this@MainActivity))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readName(intent)
        homePresses.intValue++
    }

    /** Picks up a name passed with `--es name "..."` and remembers it for the greeting. */
    private fun readName(intent: Intent) {
        intent.getStringExtra("name")?.let { UserName.set(this, it) }
        userName.value = UserName.get(this)
    }

    private fun launch(app: AppInfo) {
        try {
            startActivity(Intent(app.launchIntent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            // App was uninstalled since the list loaded; onResume will refresh it.
        }
    }
}
