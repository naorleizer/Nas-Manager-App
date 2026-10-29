package com.nasmanagerapp.ui.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nasmanagerapp.R
import com.nasmanagerapp.TrueNasApplication
import com.nasmanagerapp.data.network.TrueNasUrl
import com.nasmanagerapp.ui.ProjectInfo
import com.nasmanagerapp.ui.theme.NasManagerAppTheme

/**
 * State exposed by the login screen, owned by the caller (e.g. a ViewModel later on).
 */
data class LoginUiState(
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val rememberMe: Boolean = true,
    val acceptHttpRisks: Boolean = false,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    // Overridden so the password never appears in a log line, crash report, or debugger view that
    // prints this state via toString() — the default data class toString() would include it as-is.
    override fun toString(): String =
        "LoginUiState(serverUrl='$serverUrl', username='$username', password='***', " +
            "rememberMe=$rememberMe, acceptHttpRisks=$acceptHttpRisks, isLoading=$isLoading, " +
            "errorMessage=$errorMessage)"
}

@Composable
fun LoginRoute(onLoginSuccess: () -> Unit, modifier: Modifier = Modifier) {
    val app = LocalContext.current.applicationContext as TrueNasApplication
    val viewModel: LoginViewModel = viewModel(
        factory = remember { LoginViewModelFactory(app) },
    )
    val uiState by viewModel.uiState.collectAsState()

    LoginScreen(
        uiState = uiState,
        onServerUrlChange = viewModel::onServerUrlChange,
        onUsernameChange = viewModel::onUsernameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onRememberMeChange = viewModel::onRememberMeChange,
        onAcceptHttpRisksChange = viewModel::onAcceptHttpRisksChange,
        onConnectClick = { viewModel.connect(onSuccess = onLoginSuccess) },
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    uiState: LoginUiState,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onRememberMeChange: (Boolean) -> Unit,
    onAcceptHttpRisksChange: (Boolean) -> Unit,
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var passwordVisible by remember { mutableStateOf(false) }

    val isHttp = TrueNasUrl.isHttp(uiState.serverUrl)

    val canSubmit = uiState.serverUrl.isNotBlank() &&
        uiState.username.isNotBlank() &&
        uiState.password.isNotBlank() &&
        (!isHttp || uiState.acceptHttpRisks) &&
        !uiState.isLoading

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // The launcher foreground bitmap (the adaptive ic_launcher itself can't be drawn by
                // painterResource); it carries the icon's own tile plus the adaptive-icon safe-zone margin.
                Image(
                    painter = painterResource(R.mipmap.ic_launcher_foreground),
                    contentDescription = null,
                    modifier = Modifier.size(120.dp),
                )
                Text(
                    text = "NasManager mobile",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Log in to your TrueNAS Scale server",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(32.dp))

                LoginTextField(
                    value = uiState.serverUrl,
                    onValueChange = onServerUrlChange,
                    label = "Server address",
                    placeholder = "https://192.168.1.10",
                    icon = Icons.Filled.Dns,
                    keyboardType = KeyboardType.Uri,
                )

                Spacer(Modifier.height(12.dp))

                LoginTextField(
                    value = uiState.username,
                    onValueChange = onUsernameChange,
                    label = "Username",
                    placeholder = "admin",
                    icon = Icons.Filled.Person,
                    keyboardType = KeyboardType.Text,
                )

                Spacer(Modifier.height(12.dp))

                LoginTextField(
                    value = uiState.password,
                    onValueChange = onPasswordChange,
                    label = "Password",
                    placeholder = "",
                    icon = Icons.Filled.Lock,
                    keyboardType = KeyboardType.Password,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) {
                                    Icons.Filled.VisibilityOff
                                } else {
                                    Icons.Filled.Visibility
                                },
                                contentDescription = if (passwordVisible) {
                                    "Hide password"
                                } else {
                                    "Show password"
                                },
                            )
                        }
                    },
                )

                if (isHttp) {
                    Spacer(Modifier.height(12.dp))
                    HttpRiskWarning(
                        accepted = uiState.acceptHttpRisks,
                        onAcceptedChange = onAcceptHttpRisksChange,
                    )
                }

                if (uiState.errorMessage != null) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = uiState.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Spacer(Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.toggleable(
                            value = uiState.rememberMe,
                            onValueChange = onRememberMeChange,
                            enabled = !isHttp,
                            role = Role.Checkbox,
                        ),
                    ) {
                        Checkbox(
                            checked = uiState.rememberMe,
                            onCheckedChange = null,
                            enabled = !isHttp,
                        )
                        Text(
                            text = "Stay logged in",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (isHttp) {
                        Text(
                            text = "Not available over HTTP — your password would be sent " +
                                "automatically on any network",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 48.dp, top = 2.dp),
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = onConnectClick,
                    enabled = canSubmit,
                    modifier = Modifier
                        .widthIn(max = 400.dp)
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Log in")
                    }
                }

                Spacer(Modifier.height(24.dp))

                ProjectInfo()
            }
        }
    }
}

@Composable
private fun HttpRiskWarning(
    accepted: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                imageVector = Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "This address uses HTTP, unencrypted: your credentials and your " +
                    "data travel in the clear over the network. Prefer HTTPS if possible.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.toggleable(
                value = accepted,
                onValueChange = onAcceptedChange,
                role = Role.Checkbox,
            ),
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(
                text = "I accept the risks of using HTTP",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoginTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    icon: ImageVector,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .widthIn(max = 400.dp)
            .fillMaxWidth(),
        label = { Text(label) },
        placeholder = { if (placeholder.isNotEmpty()) Text(placeholder) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = trailingIcon,
        singleLine = true,
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780)
@Composable
private fun LoginScreenPreview() {
    NasManagerAppTheme {
        LoginScreen(
            uiState = LoginUiState(),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onRememberMeChange = {},
            onAcceptHttpRisksChange = {},
            onConnectClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "Loading + error")
@Composable
private fun LoginScreenLoadingPreview() {
    NasManagerAppTheme {
        LoginScreen(
            uiState = LoginUiState(
                serverUrl = "https://192.168.1.10",
                username = "admin",
                password = "secret",
                isLoading = true,
                errorMessage = "Couldn't reach the server.",
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onRememberMeChange = {},
            onAcceptHttpRisksChange = {},
            onConnectClick = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 780, name = "HTTP warning")
@Composable
private fun LoginScreenHttpWarningPreview() {
    NasManagerAppTheme {
        LoginScreen(
            uiState = LoginUiState(
                serverUrl = "http://192.168.1.10",
                username = "admin",
                rememberMe = false,
            ),
            onServerUrlChange = {},
            onUsernameChange = {},
            onPasswordChange = {},
            onRememberMeChange = {},
            onAcceptHttpRisksChange = {},
            onConnectClick = {},
        )
    }
}
