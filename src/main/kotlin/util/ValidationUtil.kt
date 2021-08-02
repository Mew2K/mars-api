package network.warzone.api.util

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.request.*
import io.ktor.util.pipeline.*
import network.warzone.api.http.ApiException
import network.warzone.api.http.ValidationException
import org.valiktor.Constraint
import org.valiktor.ConstraintViolationException
import org.valiktor.Validator
import java.util.*

val PLAYER_NAME_REGEX = Regex("^[a-zA-Z0-9_]{1,16}\$")
val IP_V4_REGEX = Regex("^((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)")

suspend inline fun <reified T : Any> validate(context: PipelineContext<Unit, ApplicationCall>, fn: (data: T) -> Unit) {
    try {
        val data = context.call.receive<T>()
        fn(data)
    } catch (ex: ConstraintViolationException) {
        val violation = ex.constraintViolations.first()
        throw ValidationException("Validation failed for '${violation.property}' (value: ${violation.value})")
    } catch (ex: ApiException) {
        println(ex)
        throw ex
    } catch (ex: Exception) {
        println(ex)
        throw ValidationException("Validation failed. Ensure the JSON body only contains relevant keys.")
    }
}

object PlayerName : Constraint

fun <E> Validator<E>.Property<String?>.isPlayerName() = this.validate(PlayerName) {
    if (it == null) return@validate true
    return@validate it.matches(PLAYER_NAME_REGEX)
}

object IPv4 : Constraint

fun <E> Validator<E>.Property<String?>.isIPv4() = this.validate(IPv4) {
    if (it == null) return@validate true
    return@validate it.matches(IP_V4_REGEX)
}