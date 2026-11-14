package dev.phonebridge.android.ui.connect

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import dev.phonebridge.android.ui.components.LocalSnackbarHostState
import dev.phonebridge.android.ui.components.findActivity
import dev.phonebridge.android.ui.deeplink.ConfigureLinkViewModel
import kotlinx.coroutines.launch

/**
 * "Scan QR code" for "Your own relay": reads the QR code a relay prints (or shows on its setup page) and opens
 * the same "Connect to relay?" dialog as a setup link ([ConfigureLinkViewModel.offerScanned]); nothing is
 * applied before the user confirms. Uses Google Play services' code scanner, which shows the camera itself, so
 * PhoneBridge needs no camera permission and never sees the camera image. Hidden on phones without Google Play
 * services; the relay URL form (which also takes a pasted setup link) works everywhere.
 */
@Composable
internal fun ScanSetupQrButton(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context.findActivity() as? ComponentActivity ?: return
    if (!remember { QrScanner.isAvailable(context) }) return
    val links: ConfigureLinkViewModel = viewModel(viewModelStoreOwner = activity)
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    OutlinedButton(
        onClick = { QrScanner.scan(activity, onResult = links::offerScanned, onError = { scope.launch { snackbar.showSnackbar(it) } }) },
        modifier = modifier,
    ) { Text(SCAN_LABEL) }
}

/** The activity's [ConfigureLinkViewModel] (the one behind the "Connect to relay?" dialog), or null outside an activity. */
@Composable
internal fun rememberConfigureLinks(): ConfigureLinkViewModel? {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return null
    return viewModel(viewModelStoreOwner = activity)
}

internal const val SCAN_LABEL = "Scan QR code"

/** Google's code scanner (Google Play services shows the camera UI; no camera permission). */
internal object QrScanner {
    private const val TAG = "PhoneBridge/QrScanner"

    /** False without (a working) Google Play services: the scanner can't run, so the button is hidden. */
    fun isAvailable(context: Context): Boolean = try {
        GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
    } catch (e: RuntimeException) {
        false
    }

    /** Scans one QR code: [onResult] with its text; nothing when the user backs out; [onError] with a message to show. */
    fun scan(activity: Activity, onResult: (String?) -> Unit, onError: (String) -> Unit) {
        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build()
        val scanner = GmsBarcodeScanning.getClient(activity, options)
        scanner.startScan()
            .addOnSuccessListener { barcode -> onResult(barcode.rawValue) }
            .addOnFailureListener { e ->
                Log.w(TAG, "QR scan failed", e)
                if (e is MlKitException && e.errorCode == MlKitException.UNAVAILABLE) {
                    // The scanner is an optional Play services module: ask for it, then the user tries again.
                    ModuleInstall.getClient(activity).installModules(ModuleInstallRequest.newBuilder().addApi(scanner).build())
                    onError("Google Play services is downloading the QR scanner. Try again in a minute, or paste the setup link.")
                } else {
                    onError("Couldn't open the QR scanner. Paste the setup link or enter the relay URL instead.")
                }
            }
    }
}
