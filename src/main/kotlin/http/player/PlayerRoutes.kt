package http.player

import io.ktor.application.*
import io.ktor.http.*
import io.ktor.response.*
import io.ktor.routing.*
import network.warzone.api.database.Database
import network.warzone.api.database.PlayerCache
import network.warzone.api.database.findById
import network.warzone.api.database.findByIdOrName
import network.warzone.api.database.models.*
import network.warzone.api.http.*
import network.warzone.api.http.player.PlayerLoginRequest
import network.warzone.api.http.player.PlayerLoginResponse
import network.warzone.api.http.player.PlayerLogoutRequest
import network.warzone.api.http.player.PlayerSetActiveTagRequest
import network.warzone.api.http.punishment.PunishmentIssueRequest
import network.warzone.api.util.validate
import org.litote.kmongo.contains
import org.litote.kmongo.div
import org.litote.kmongo.eq
import java.security.MessageDigest
import java.util.*

fun Route.playerSessions() {
    post("/login") {
        validate<PlayerLoginRequest>(this) { data ->
            val now = Date().time
            val ip = hashSHA256(data.ip)
            val activeSession =
                Session(playerId = data.playerId, createdAt = now, endedAt = null, _id = UUID.randomUUID().toString())

            val returningPlayer = Database.players.findById(data.playerId)

            // Player has joined before
            if (returningPlayer !== null) {
                // todo: account for multi-server. kick player from server if they're joining a diff server.
                // Delete any active sessions the player may have. Sessions should always be ended when the player leaves.
                Database.sessions.deleteMany(Session::endedAt eq null, Session::playerId eq data.playerId)

                returningPlayer.name = data.playerName
                returningPlayer.nameLower = returningPlayer.name.lowercase()
                returningPlayer.ips =
                    if (ip in returningPlayer.ips) returningPlayer.ips else returningPlayer.ips + ip

                val ranksWithDefault = returningPlayer.rankIds + Rank.findDefault().map { it._id }
                returningPlayer.rankIds = ranksWithDefault.distinct()

                val playerPunishments = returningPlayer.getActivePunishments()
                val playerBan = playerPunishments.firstOrNull { it.action.isBan }
                val ipPunishments = if (playerBan != null) Database.punishments.find(
                    Punishment::targetIps contains ip,
                    Punishment::action / PunishmentAction::kind eq PunishmentKind.IP_BAN
                ).toList() else emptyList()
                val ipBan = ipPunishments.firstOrNull()

                val banned = playerBan != null || ipBan != null

                if (!banned) {
                    returningPlayer.lastJoinedAt = now
                    Database.sessions.save(activeSession)
                }

                PlayerCache.set(returningPlayer.name, returningPlayer, persist = true)

                call.respond(
                    PlayerLoginResponse(
                        player = returningPlayer,
                        if (!banned) activeSession else null,
                        playerPunishments + ipPunishments
                    )
                )

                Player.ensureNameUniqueness(data.playerName, data.playerId)
            } else { // Player is new!
                val player = Player(
                    _id = data.playerId,
                    name = data.playerName,
                    nameLower = data.playerName.lowercase(),
                    ips = listOf(ip),
                    firstJoinedAt = now,
                    lastJoinedAt = now,
                    rankIds = Rank.findDefault().map { it._id },
                    tagIds = emptyList(),
                    activeTagId = null,
                    stats = PlayerStats()
                )

                PlayerCache.set(player.name, player, persist = true)
                Database.sessions.save(activeSession)

                call.respond(HttpStatusCode.Created, PlayerLoginResponse(player, activeSession, emptyList()))

                Player.ensureNameUniqueness(data.playerName, data.playerId)
            }
        }
    }

    post("/logout") {
        validate<PlayerLogoutRequest>(this) { data ->
            val player: Player = PlayerCache.get(data.playerId) ?: throw PlayerMissingException()
            val activeSession = player.getActiveSession() ?: throw SessionInactiveException()

            activeSession.endedAt = Date().time
            player.stats.serverPlaytime += data.playtime

            Database.sessions.save(activeSession)
            PlayerCache.set(player.name, player, persist = true)

            call.respond(Unit)
        }
    }

    get("/{playerId}") {
        val playerId = call.parameters["playerId"]?.lowercase() ?: throw ValidationException()
        val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()

        call.respond(player)
    }
}

fun Route.playerPunishments() {
    post("/{playerId}/punishments") {
        validate<PunishmentIssueRequest>(this) { data ->
            val id = UUID.randomUUID().toString()
            val now = Date().time
            val target: Player = PlayerCache.get(data.targetName) ?: throw PlayerMissingException()
            val punishment = Punishment(
                _id = id,
                reason = data.reason,
                issuedAt = now,
                offence = data.offence,
                action = data.action,
                note = data.note,
                punisher = data.punisher,
                target = target.simple,
                targetIps = data.targetIps,
                silent = data.silent
            )
            Database.punishments.insertOne(punishment)
            call.respond(punishment)
        }
    }

    get("/{playerId}/punishments") {
        val playerId = call.parameters["playerId"] ?: throw ValidationException()
        val player = PlayerCache.get<Player>(playerId) ?: throw PlayerMissingException()
        call.respond(player.getPunishments())
    }
}

fun Route.playerTags() {
    put("/{playerId}/active_tag") {
        validate<PlayerSetActiveTagRequest>(this) { data ->
            val playerId = call.parameters["playerId"] ?: throw ValidationException()
            val tagId = data.activeTagId

            val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()

            if (tagId == player.activeTagId) return@put call.respond(player)

            if (tagId == null) {
                player.activeTagId = null
            } else {
                if (tagId !in player.tagIds) throw TagNotPresentException()
                player.activeTagId = tagId
            }

            PlayerCache.set(player.name, player, persist = true)
            call.respond(player)
        }
    }

    put("/{playerId}/tags/{tagId}") {
        val playerId = call.parameters["playerId"] ?: throw ValidationException()
        val tagId = call.parameters["tagId"] ?: throw ValidationException()

        val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()
        val tag = Database.tags.findByIdOrName(tagId) ?: throw TagMissingException()

        if (tag._id in player.tagIds) throw TagAlreadyPresentException()
        player.tagIds = player.tagIds + tag._id

        PlayerCache.set(player.name, player, persist = true)
        call.respond(player)
    }

    delete("/{playerId}/tags/{tagId}") {
        val playerId = call.parameters["playerId"] ?: throw ValidationException()
        val tagId = call.parameters["tagId"] ?: throw ValidationException()

        val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()
        val tag = Database.tags.findByIdOrName(tagId) ?: throw TagMissingException()

        if (tag._id !in player.tagIds) throw TagNotPresentException()
        player.tagIds = player.tagIds.filterNot { it == tag._id }
        if (player.activeTagId == tag._id) player.activeTagId = null

        PlayerCache.set(player.name, player, persist = true)
        call.respond(player)
    }
}

fun Route.playerRanks() {
    put("/{playerId}/ranks/{rankId}") {
        val playerId = call.parameters["playerId"] ?: throw ValidationException()
        val rankId = call.parameters["rankId"] ?: throw ValidationException()

        val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()
        val rank = Database.ranks.findById(rankId) ?: throw RankMissingException()

        if (rank._id in player.rankIds) throw RankAlreadyPresentException()
        player.rankIds = player.rankIds + rank._id

        PlayerCache.set(player.name, player, persist = true)
        call.respond(player)
    }

    delete("/{playerId}/ranks/{rankId}") {
        val playerId = call.parameters["playerId"] ?: throw ValidationException()
        val rankId = call.parameters["rankId"] ?: throw ValidationException()

        val player: Player = PlayerCache.get(playerId) ?: throw PlayerMissingException()
        val rank = Database.ranks.findById(rankId) ?: throw RankMissingException()

        if (rank._id !in player.rankIds) throw RankNotPresentException()
        player.rankIds = player.rankIds.filterNot { it == rank._id }

        PlayerCache.set(player.name, player, persist = true)
        call.respond(player)
    }
}

fun Application.playerRoutes() {
    routing {
        route("/mc/players") {
            playerSessions()
            playerRanks()
            playerTags()
            playerPunishments()
        }
    }
}

fun hashSHA256(ip: String) =
    MessageDigest.getInstance("SHA-256").digest(ip.toByteArray()).fold("") { str, it -> str + "%02x".format(it) }