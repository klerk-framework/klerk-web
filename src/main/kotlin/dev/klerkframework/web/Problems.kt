package dev.klerkframework.web

import dev.klerkframework.klerk.AuthorizationProblem
import dev.klerkframework.klerk.BadRequestProblem
import dev.klerkframework.klerk.IdempotenceProblem
import dev.klerkframework.klerk.InternalProblem
import dev.klerkframework.klerk.InvalidPropertyCollectionProblem
import dev.klerkframework.klerk.InvalidPropertyProblem
import dev.klerkframework.klerk.NotFoundProblem
import dev.klerkframework.klerk.PreventedByRuleProblem
import dev.klerkframework.klerk.Problem
import dev.klerkframework.klerk.ServerStateProblem
import dev.klerkframework.klerk.StateProblem
import io.ktor.http.HttpStatusCode

/** The HTTP status to respond with when a command fails with this problem. */
public val Problem.httpStatus: HttpStatusCode
    get() = when (this) {
        is InvalidPropertyProblem, is InvalidPropertyCollectionProblem, is PreventedByRuleProblem,
        is BadRequestProblem, is IdempotenceProblem -> HttpStatusCode.BadRequest
        is AuthorizationProblem -> HttpStatusCode.Forbidden
        is NotFoundProblem -> HttpStatusCode.NotFound
        is StateProblem -> HttpStatusCode.Conflict
        is InternalProblem -> HttpStatusCode.InternalServerError
        is ServerStateProblem -> HttpStatusCode.ServiceUnavailable
    }
