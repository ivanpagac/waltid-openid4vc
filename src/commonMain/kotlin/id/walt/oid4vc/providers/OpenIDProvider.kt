package id.walt.oid4vc.providers

import id.walt.oid4vc.data.GrantType
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.oid4vc.definitions.JWTClaims
import id.walt.oid4vc.interfaces.ISessionCache
import id.walt.oid4vc.interfaces.ITokenProvider
import id.walt.oid4vc.requests.AuthorizationRequest
import id.walt.oid4vc.requests.TokenRequest
import id.walt.oid4vc.responses.*
import id.walt.oid4vc.util.randomUUID
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.json.*

abstract class OpenIDProvider<S: AuthorizationSession>(
  val baseUrl: String,
): ISessionCache<S>, ITokenProvider {
  abstract val metadata: OpenIDProviderMetadata
  abstract val config: OpenIDProviderConfig

  fun getCommonProviderMetadataUrl(): String {
    return URLBuilder(baseUrl).apply {
      pathSegments = listOf(".well-known", "openid-configuration")
    }.buildString()
  }

  protected open fun generateToken(sub: String, audience: TokenTarget, tokenId: String? = null): String {
    return signToken(audience, buildJsonObject {
      put(JWTClaims.Payload.subject, sub);
      put(JWTClaims.Payload.issuer, metadata.issuer)
      put(JWTClaims.Payload.audience, audience.name)
      tokenId?.let { put(JWTClaims.Payload.jwtID, it) }
    })
  }

  protected open fun verifyAndParseToken(token: String, target: TokenTarget): JsonObject? {
    if(verifyTokenSignature(target, token)) {
      val payload = parseTokenPayload(token)
      if(payload.keys.containsAll(setOf(JWTClaims.Payload.subject, JWTClaims.Payload.audience, JWTClaims.Payload.issuer)) &&
          payload[JWTClaims.Payload.audience]!!.jsonPrimitive.content == target.name &&
          payload[JWTClaims.Payload.issuer]!!.jsonPrimitive.content == metadata.issuer
        ) {
        return payload
      }
    }
    return null
  }

  protected open fun generateAuthorizationCodeFor(session: S): String {
    return generateToken(session.id, TokenTarget.TOKEN)
  }

  protected open fun validateAuthorizationCode(code: String): JsonObject? {
    return verifyAndParseToken(code, TokenTarget.TOKEN)
  }

  protected abstract fun validateAuthorizationRequest(authorizationRequest: AuthorizationRequest): Boolean

  abstract fun initializeAuthorization(authorizationRequest: AuthorizationRequest, expiresIn: Int): S
  open fun continueAuthorization(authorizationSession: S): AuthorizationResponse {
    val code = generateAuthorizationCodeFor(authorizationSession)
    return AuthorizationResponse.success(code)
  }

  protected open fun generateTokenResponse(session: S, tokenRequest: TokenRequest): TokenResponse {
    return TokenResponse.success(
      generateToken(session.id, TokenTarget.ACCESS),
      "bearer"
    )
  }

  protected fun getVerifiedSession(sessionId: String): S? {
    return getSession(sessionId)?.let {
      if(it.isExpired) {
        removeSession(sessionId)
        null
      } else {
        it
      }
    }
  }

  open fun processTokenRequest(tokenRequest: TokenRequest): TokenResponse {
    val code = when(tokenRequest.grantType) {
      GrantType.authorization_code -> tokenRequest.code ?: throw TokenError(tokenRequest, TokenErrorCode.invalid_grant, "No code parameter found on token request")
      GrantType.pre_authorized_code -> tokenRequest.preAuthorizedCode ?: throw TokenError(tokenRequest, TokenErrorCode.invalid_grant, "No pre-authorized_code parameter found on token request")
      else -> throw TokenError(tokenRequest, TokenErrorCode.unsupported_grant_type, "Grant type not supported")
    }
    val payload = validateAuthorizationCode(code) ?: throw TokenError(tokenRequest, TokenErrorCode.invalid_grant, "Authorization code could not be verified")

    val sessionId = payload["sub"]!!.jsonPrimitive.content
    val session = getVerifiedSession(sessionId) ?: throw TokenError(tokenRequest, TokenErrorCode.invalid_request, "No authorization session found for given authorization code, or session expired.")

    return generateTokenResponse(session, tokenRequest)
  }

  fun getPushedAuthorizationSuccessResponse(authorizationSession: S) = PushedAuthorizationResponse.success(
    requestUri = "urn:ietf:params:oauth:request_uri:${authorizationSession.id}",
    expiresIn = authorizationSession.expirationTimestamp - Clock.System.now().epochSeconds
  )

  fun getPushedAuthorizationSession(authorizationRequest: AuthorizationRequest): S {
    val session = authorizationRequest.requestUri?.let {
      getVerifiedSession(
        it.substringAfter("urn:ietf:params:oauth:request_uri:")
      ) ?: throw AuthorizationError(authorizationRequest, AuthorizationErrorCode.invalid_request,"No session found for given request URI, or session expired")
    } ?: throw AuthorizationError(authorizationRequest, AuthorizationErrorCode.invalid_request, "Authorization request does not refer to a pushed authorization session")

    return session
  }

  fun validateAccessToken(accessToken: String): Boolean {
    return verifyAndParseToken(accessToken, TokenTarget.ACCESS) != null
  }

}