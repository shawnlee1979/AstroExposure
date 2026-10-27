package app.astroexposure

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import app.astroexposure.ui.NpfScreen
import app.astroexposure.ui.TonightScreen
import app.astroexposure.ui.theme.AstroTheme
import java.time.LocalDate
import java.util.Locale

/**
 * Phase-1 walking skeleton: tonight overview + NPF calculator, red-light
 * toggle, zh/en switch. Time ("today") is read from the device clock HERE,
 * at the application boundary, and passed down as a value — the domain and
 * the ephemeris port never read the clock themselves (design §4.1, D.2).
 */
class MainActivity : AppCompatActivity() {

    private val container = AppContainer()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AstroApp(container)
        }
    }
}

private enum class Tab { TONIGHT, NPF }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AstroApp(container: AppContainer) {
    var redLight by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableStateOf(Tab.TONIGHT) }

    val location = AppContainer.DEFAULT_LOCATION
    val today = remember { LocalDate.now(location.zone) }
    val overview = remember(today) { container.tonightOverview.execute(location, today) }

    AstroTheme(redLight = redLight) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    actions = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(end = 8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.red_light),
                                modifier = Modifier.padding(end = 4.dp),
                            )
                            Switch(checked = redLight, onCheckedChange = { redLight = it })
                            TextButton(onClick = { toggleLanguage() }) {
                                Text(stringResource(R.string.language_switch))
                            }
                        }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.TONIGHT,
                        onClick = { tab = Tab.TONIGHT },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_tonight)) },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.NPF,
                        onClick = { tab = Tab.NPF },
                        icon = {},
                        label = { Text(stringResource(R.string.tab_npf)) },
                    )
                }
            },
        ) { padding ->
            when (tab) {
                Tab.TONIGHT -> TonightScreen(
                    overview = overview,
                    date = today,
                    zone = location.zone,
                    modifier = Modifier.padding(padding),
                )
                Tab.NPF -> NpfScreen(
                    useCase = container.npfCalculator,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** Switch the per-app locale between Chinese (default) and English, applied immediately. */
private fun toggleLanguage() {
    val current = AppCompatDelegate.getApplicationLocales()
    val isEnglish = !current.isEmpty && current.get(0)?.language == "en"
    val next = if (isEnglish) Locale("zh") else Locale("en")
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(next))
}
