package network.warzone.api.database

import org.litote.kmongo.json
import redis.clients.jedis.params.SetParams

open class Cache(val resourceName: String, val lifetimeMs: Long? = null) {
    suspend inline fun <reified T : Any> get(key: String): T? {
        val cachedValue = Redis.get<T>("$resourceName:$key")
        if (cachedValue !== null) return cachedValue

        return Database.database.getCollection<T>()
            .findOne("{ \$or: [ { nameLower: ${key.lowercase().json} }, { _id: ${key.json} } ] }")
    }

    suspend inline fun <reified T : Any> set(
        key: String,
        value: T,
        persist: Boolean = false,
        options: SetParams? = if (lifetimeMs != null) SetParams().px(lifetimeMs) else SetParams()
    ) {
        Redis.set("$resourceName:$key", value, options)
        if (persist) Database.database.getCollection<T>().save(value)
    }

    suspend inline fun <reified T : Any> persist(key: String) {
        val value: T? = get(key)
        if (value != null) Database.database.getCollection<T>().save(value)
    }
}

// Cached match expires after one day
const val MATCH_LIFETIME = 86400000L // MS

object PlayerCache : Cache("player")
object MatchCache : Cache("match", MATCH_LIFETIME)