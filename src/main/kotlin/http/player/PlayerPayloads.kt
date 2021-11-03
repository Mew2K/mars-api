package network.warzone.api.http.player

import kotlinx.serialization.Serializable
import network.warzone.api.database.models.Player
import network.warzone.api.database.models.Session

@Serializable
data class PlayerLoginRequest(
    val playerId: String,
    val playerName: String,
    val ip: String
)

@Serializable
data class PlayerLoginResponse(
    val player: Player,
    val activeSession: Session?
)

@Serializable
data class PlayerLogoutRequest(val playerId: String, val playtime: Long)

@Serializable
data class PlayerSetActiveTagRequest(val activeTagId: String? = null)