package com.hyperion.ui.screen.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.Start
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.hyperion.R
import com.hyperion.ui.component.setting.RadioSetting
import com.hyperion.ui.component.setting.SwitchSetting
import com.hyperion.ui.navigation.RootDestination
import com.hyperion.ui.viewmodel.SettingsViewModel

context(ColumnScope)
@Composable
fun GeneralScreen(
    viewModel: SettingsViewModel
) {
    val preferences = viewModel.preferences

    RadioSetting(
        icon = Icons.Default.Start,
        label = stringResource(R.string.start_screen),
        value = preferences.startScreen,
        options = RootDestination.values().associateBy { destination ->
            stringResource(destination.label)
        },
        onConfirm = { preferences.startScreen = it }
    )

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        SwitchSetting(
            enabled = false,
            checked = preferences.pictureInPicture,
            text = stringResource(R.string.pip),
            icon = Icons.Default.PictureInPicture,
            onCheckedChange = { preferences.pictureInPicture = it }
        )
    }

    val directoryChooser =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) preferences.downloadDirectory = uri.toString()
        }

    ListItem(
        modifier = Modifier.clickable { directoryChooser.launch(null) },
        headlineText = { Text(stringResource(R.string.download_location)) },
        supportingText = { Text(preferences.downloadDirectory) },
        leadingContent = {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = stringResource(R.string.download_location)
            )
        }
    )
}