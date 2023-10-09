package id.walt.oid4vc

import id.walt.core.crypto.utils.JwsUtils.decodeJws
import id.walt.credentials.w3c.PresentableCredential
import id.walt.custodian.Custodian
import id.walt.model.DidMethod
import id.walt.oid4vc.data.OpenIDProviderMetadata
import id.walt.oid4vc.data.dif.DescriptorMapping
import id.walt.oid4vc.data.dif.PresentationDefinition
import id.walt.oid4vc.data.dif.PresentationSubmission
import id.walt.oid4vc.data.dif.VCFormat
import id.walt.oid4vc.errors.PresentationError
import id.walt.oid4vc.interfaces.PresentationResult
import id.walt.oid4vc.providers.CredentialWalletConfig
import id.walt.oid4vc.providers.OpenIDCredentialWallet
import id.walt.oid4vc.providers.SIOPSession
import id.walt.oid4vc.providers.TokenTarget
import id.walt.oid4vc.requests.AuthorizationRequest
import id.walt.oid4vc.requests.TokenRequest
import id.walt.oid4vc.responses.TokenErrorCode
import id.walt.services.did.DidService
import id.walt.services.jwt.JwtService
import id.walt.services.key.KeyService
import io.kotest.common.runBlocking
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.datetime.Instant
import kotlinx.serialization.json.*

const val EBSI_WALLET_PORT = 8011
const val EBSI_WALLET_BASE_URL = "http://localhost:${EBSI_WALLET_PORT}"
const val EBSI_WALLET_TEST_KEY = "{\"kty\":\"OKP\",\"d\":\"2jDhK3fFep3oaqcVr8548CLJ3_8bgxG9z8DBL6PIrGI\",\"use\":\"sig\",\"crv\":\"Ed25519\",\"kid\":\"ed757b38cbf34afbb1f157c5e2bf08f8\",\"x\":\"1nve9sZ_SmDpuo3A5x4ccjKan5Up2qvMg7qsXOtXVkU\",\"alg\":\"EdDSA\"}"
const val EBSI_WALLET_TEST_DID = "did:key:zmYg9bgKmRiCqTTd9MA1ufVE9tfzUptwQp4GMRxptXquJWw4Uj5bVzbAR3ScDrvTYPMZzRCCyZUidTqbgTvbDjZDEzf3XwwVPothBG3iX7xxc9r1A"
class EBSITestWallet(config: CredentialWalletConfig): OpenIDCredentialWallet<SIOPSession>(EBSI_WALLET_BASE_URL, config) {
  private val sessionCache = mutableMapOf<String, SIOPSession>()
  private val ktorClient = HttpClient(Java) {
    install(ContentNegotiation) {
      json()
    }
  }

  val TEST_DID = EBSI_WALLET_TEST_DID

  init {
    if(!KeyService.getService().hasKey(EBSI_WALLET_TEST_DID)) {
      val keyId = KeyService.getService().importKey(EBSI_WALLET_TEST_KEY)
      KeyService.getService().addAlias(keyId, EBSI_WALLET_TEST_DID)
      val didDoc = DidService.resolve(EBSI_WALLET_TEST_DID)
      KeyService.getService().addAlias(keyId, didDoc.verificationMethod!!.first().id)
    }
  }
  override fun resolveDID(did: String): String {
    val didObj = DidService.resolve(did)
    return (didObj.authentication ?: didObj.assertionMethod ?: didObj.verificationMethod)?.firstOrNull()?.id ?: did
  }

  override fun resolveJSON(url: String): JsonObject? {
    return runBlocking { ktorClient.get(url).body() }
  }

  override fun isPresentationDefinitionSupported(presentationDefinition: PresentationDefinition): Boolean {
    return true
  }

  override fun createSIOPSession(
    id: String,
    authorizationRequest: AuthorizationRequest?,
    expirationTimestamp: Instant
  ) = SIOPSession(id, authorizationRequest, expirationTimestamp)

  override val metadata: OpenIDProviderMetadata
    get() = createDefaultProviderMetadata()

  override fun getSession(id: String): SIOPSession? = sessionCache[id]

  override fun removeSession(id: String): SIOPSession?  = sessionCache.remove(id)

  override fun signToken(target: TokenTarget, payload: JsonObject, header: JsonObject?, keyId: String?) =
    JwtService.getService().sign(payload, keyId)

  override fun verifyTokenSignature(target: TokenTarget, token: String) =
    JwtService.getService().verify(token).verified

  override fun generatePresentationForVPToken(session: SIOPSession, tokenRequest: TokenRequest): PresentationResult {
    val presentationDefinition = session.presentationDefinition ?: throw PresentationError(TokenErrorCode.invalid_request, tokenRequest, session.presentationDefinition)
    val filterString = presentationDefinition.inputDescriptors.flatMap { it.constraints?.fields ?: listOf() }
      .firstOrNull { field -> field.path.any { it.contains("type") } }?.filter?.jsonObject.toString()
    val presentationJwtStr = Custodian.getService()
      .createPresentation(
        Custodian.getService().listCredentials().filter { filterString.contains(it.type.last()) }.map {
          PresentableCredential(
            it,
            selectiveDisclosure = null,
            discloseAll = false
          )
        }, TEST_DID, challenge = session.nonce
      )

    println("================")
    println("PRESENTATION IS: $presentationJwtStr")
    println("================")

    val presentationJws = presentationJwtStr.decodeJws()
    val jwtCredentials =
      ((presentationJws.payload["vp"]
        ?: throw IllegalArgumentException("VerifiablePresentation string does not contain `vp` attribute?"))
        .jsonObject["verifiableCredential"]
        ?: throw IllegalArgumentException("VerifiablePresentation does not contain verifiableCredential list?"))
        .jsonArray.map { it.jsonPrimitive.content }
    return PresentationResult(
      listOf(JsonPrimitive(presentationJwtStr)), PresentationSubmission(
        id = "submission 1",
        definitionId = session.presentationDefinition!!.id,
        descriptorMap = jwtCredentials.mapIndexed { index, vcJwsStr ->

          val vcJws = vcJwsStr.decodeJws()
          val type =
            vcJws.payload["vc"]?.jsonObject?.get("type")?.jsonArray?.last()?.jsonPrimitive?.contentOrNull
              ?: "VerifiableCredential"

          DescriptorMapping(
            id = type,
            format = VCFormat.jwt_vp,  // jwt_vp_json
            path = "$",
            pathNested = DescriptorMapping(
              format = VCFormat.jwt_vc,
              path = "$.vp.verifiableCredential[0]",
            )
          )
        }
      )
    )
  }

  override fun putSession(id: String, session: SIOPSession): SIOPSession? = sessionCache.put(id, session)


}