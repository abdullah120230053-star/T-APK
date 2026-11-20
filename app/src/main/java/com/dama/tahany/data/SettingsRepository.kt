package com.dama.tahany.data

import android.content.Context
import android.os.Build
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.dama.tahany.model.AppSettings
import com.dama.tahany.model.DeviceRole
import com.dama.tahany.model.TrustedPeer
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID

private val Context.tahanyDataStore by preferencesDataStore(name = "tahany_settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val ROLE = stringPreferencesKey("role")
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DEVICE_NAME = stringPreferencesKey("device_name")
        val SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        val AUTO_SCREENSHOT = booleanPreferencesKey("auto_screenshot")
        val TRUSTED_PEERS = stringSetPreferencesKey("trusted_peers")
        val BLOCKED_PACKAGES = stringSetPreferencesKey("blocked_packages")
    }

    companion object {
        val DEFAULT_BLOCKED_PACKAGES = setOf(
            "com.google.android.apps.authenticator2",
            "com.authenticator.authservice2",
            "com.onepassword.android",
            "com.x8bit.bitwarden",
            "keepass2android.keepass2android",
        )
    }

    val settings: Flow<AppSettings> = context.tahanyDataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map(::toSettings)

    suspend fun current(): AppSettings = settings.first()

    /**
     * Creates the app-scoped identity on first launch. It is deliberately not
     * derived from a serial number, MAC address, or any hardware identifier.
     */
    suspend fun ensureInitialized() {
        val snapshot = context.tahanyDataStore.data.first()
        if (snapshot[Keys.DEVICE_ID].isNullOrBlank()) {
            context.tahanyDataStore.edit { preferences ->
                if (preferences[Keys.DEVICE_ID].isNullOrBlank()) {
                    preferences[Keys.DEVICE_ID] = UUID.randomUUID().toString()
                }
                if (preferences[Keys.DEVICE_NAME].isNullOrBlank()) {
                    preferences[Keys.DEVICE_NAME] = defaultDeviceName()
                }
            }
        }
    }

    suspend fun completeSetup() {
        context.tahanyDataStore.edit { it[Keys.SETUP_COMPLETE] = true }
    }

    suspend fun setRole(role: DeviceRole) {
        context.tahanyDataStore.edit { it[Keys.ROLE] = role.name }
    }

    suspend fun setAutoScreenshot(enabled: Boolean) {
        context.tahanyDataStore.edit { it[Keys.AUTO_SCREENSHOT] = enabled }
    }

    suspend fun setDeviceName(name: String) {
        val clean = name.trim().take(48)
        if (clean.isNotEmpty()) context.tahanyDataStore.edit { it[Keys.DEVICE_NAME] = clean }
    }

    suspend fun trustPeer(peer: TrustedPeer) {
        context.tahanyDataStore.edit { preferences ->
            val peers = readPeers(preferences)
                .filterNot { TrustedPeerCodec.decode(it)?.deviceId == peer.deviceId }
            preferences[Keys.TRUSTED_PEERS] = (peers + TrustedPeerCodec.encode(peer)).toSet()
        }
    }

    suspend fun forgetPeer(deviceId: String) {
        context.tahanyDataStore.edit { preferences ->
            preferences[Keys.TRUSTED_PEERS] = readPeers(preferences)
                .filterNot { TrustedPeerCodec.decode(it)?.deviceId == deviceId }
                .toSet()
        }
    }

    suspend fun setPackageBlocked(packageName: String, blocked: Boolean) {
        context.tahanyDataStore.edit { preferences ->
            val current = preferences[Keys.BLOCKED_PACKAGES] ?: DEFAULT_BLOCKED_PACKAGES
            val updated = if (blocked) current + packageName else current - packageName
            preferences[Keys.BLOCKED_PACKAGES] = updated
        }
    }

    suspend fun isPackageBlocked(packageName: String): Boolean {
        val current = current().blockedPackages
        return current.contains(packageName)
    }

    private fun toSettings(preferences: Preferences): AppSettings = AppSettings(
        role = preferences[Keys.ROLE]?.let { runCatching { DeviceRole.valueOf(it) }.getOrNull() },
        deviceId = preferences[Keys.DEVICE_ID].orEmpty(),
        deviceName = preferences[Keys.DEVICE_NAME].orEmpty(),
        setupComplete = preferences[Keys.SETUP_COMPLETE] ?: false,
        autoScreenshot = preferences[Keys.AUTO_SCREENSHOT] ?: true,
        trustedPeers = readPeers(preferences).mapNotNull(TrustedPeerCodec::decode),
        blockedPackages = preferences[Keys.BLOCKED_PACKAGES] ?: DEFAULT_BLOCKED_PACKAGES,
    )

    private fun readPeers(preferences: Preferences): List<String> =
        preferences[Keys.TRUSTED_PEERS]?.toList().orEmpty()

    private fun defaultDeviceName(): String {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        return when {
            model.isBlank() -> "Tahany device"
            manufacturer.isBlank() || model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }.take(48)
    }
}

object TrustedPeerCodec {
    private val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
    private val decoder = java.util.Base64.getUrlDecoder()

    fun encode(peer: TrustedPeer): String =
        encoder.encodeToString(peer.deviceId.toByteArray(Charsets.UTF_8)) + "|" +
            encoder.encodeToString(peer.name.toByteArray(Charsets.UTF_8))

    fun decode(value: String): TrustedPeer? {
        val parts = value.split('|', limit = 2)
        if (parts.size != 2) return null
        return try {
            val id = decoder.decode(parts[0]).toString(Charsets.UTF_8)
            val name = decoder.decode(parts[1]).toString(Charsets.UTF_8)
            if (id.isBlank()) null else TrustedPeer(id, name.ifBlank { "Paired device" })
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
