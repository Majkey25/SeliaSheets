package com.majkeylab.seliadocs.settings

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.majkeylab.seliadocs.BuildConfig
import com.majkeylab.seliadocs.R
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val PRIVACY_URL = "https://majkey25.github.io/SeliaSheets/privacy/"
internal const val SOURCE_URL = "https://github.com/Majkey25/SeliaSheets"

@Composable
internal fun AppDetailsSection() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val supportNotice = stringResource(R.string.support_notice)
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 18.dp)) {
        Text(
            stringResource(R.string.version_name, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            stringResource(R.string.offline_disclosure),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        TextButton(onClick = { uriHandler.openUri(PRIVACY_URL) }) {
            Text(stringResource(R.string.privacy_policy))
        }
        TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) {
            Text(stringResource(R.string.source_code))
        }
        TextButton(onClick = { showLicenses = true }, modifier = Modifier.testTag("open-source-licenses")) {
            Text(stringResource(R.string.presentation_licenses_title))
        }
        Text(
            stringResource(R.string.third_party_notices),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
        )
        Button(
            onClick = {
                Toast.makeText(context, supportNotice, Toast.LENGTH_SHORT).show()
                uriHandler.openUri(SUPPORT_URL)
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            border = BorderStroke(1.dp, Color(0xFF111111)),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFFDD00),
                    contentColor = Color(0xFF111111),
                ),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_coffee),
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.support_app))
        }
    }
    if (showLicenses) PresentationLicensesDialog(onDismiss = { showLicenses = false })
}

@Composable
private fun PresentationLicensesDialog(onDismiss: () -> Unit) {
    val assets = LocalContext.current.assets
    val licenseText by produceState<Result<String>?>(null, assets) {
        value = withContext(Dispatchers.IO) {
            try {
                val files = assets.list("presentation/licenses")?.filter { it.endsWith(".txt") }?.sorted().orEmpty()
                if (files.isEmpty()) throw IOException("Missing bundled licenses")
                Result.success(buildString {
                    append(assets.open("presentation/NOTICE.txt").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    for (file in files) {
                        append("\n\n").append(file.removeSuffix(".txt")).append("\n\n")
                        append(assets.open("presentation/licenses/$file").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    }
                })
            } catch (error: IOException) {
                Result.failure(error)
            }
        }
    }
    AlertDialog(
        modifier = Modifier.testTag("presentation-licenses-dialog"),
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.presentation_licenses_title)) },
        text = {
            SelectionContainer {
                Text(
                    text = licenseText?.getOrNull() ?: stringResource(
                        if (licenseText == null) R.string.presentation_licenses_loading else R.string.presentation_licenses_failed,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())
                        .testTag("presentation-licenses-text"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
