package com.saeed.ikev2vpn.ui

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.material3.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation

@Composable
fun ProfilePasswordDialog(profileName: String, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var password by remember(profileName) { mutableStateOf("") }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) password = ""
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); password = "" }
    }
    AlertDialog(
        onDismissRequest = { password = ""; onDismiss() },
        title = { Text("Password for $profileName") },
        text = { OutlinedTextField(value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)) },
        confirmButton = { TextButton(enabled = password.isNotEmpty(), onClick = {
            val submitted = password
            password = ""
            onSubmit(submitted)
        }) { Text("Select Profile") } },
        dismissButton = { TextButton(onClick = { password = ""; onDismiss() }) { Text("Cancel") } },
    )
}
