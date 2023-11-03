package id.walt.oid4vc

import id.walt.credentials.w3c.VerifiableCredential
import id.walt.custodian.Custodian
import id.walt.oid4vc.providers.CredentialWalletConfig
import id.walt.oid4vc.providers.OpenIDClientConfig
import id.walt.oid4vc.requests.CredentialOfferRequest
import id.walt.oid4vc.responses.CredentialResponse
import id.walt.oid4vc.util.randomUUID
import id.walt.servicematrix.ServiceMatrix
import io.kotest.common.runBlocking
import io.kotest.core.spec.style.StringSpec
import io.kotest.data.forAll
import io.kotest.data.row
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.*
import io.ktor.client.engine.java.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.util.*
import kotlinx.serialization.json.jsonPrimitive

class EBSI_Conformance_Test: StringSpec({

  val VcTestsEnabled = true
  val VpTestsEnabled = true

  lateinit var credentialWallet: EBSITestWallet
  lateinit var ebsiClientConfig: OpenIDClientConfig

  val ktorClient = HttpClient(Java) {
    install(ContentNegotiation) {
      json()
    }
    followRedirects = false
  }

  val crossDeviceCredentialOfferRequestCaller: credentialOfferRequestCaller = { initCredentialOfferUrl ->
    val inTimeCredentialOfferRequestUri = runBlocking { ktorClient.get(initCredentialOfferUrl).bodyAsText() }
    CredentialOfferRequest.fromHttpQueryString(Url(inTimeCredentialOfferRequestUri).encodedQuery)
  }

  val sameDeviceCredentialOfferRequestCaller: credentialOfferRequestCaller = { initCredentialOfferUrl ->
    val httpResp = runBlocking { ktorClient.get(initCredentialOfferUrl) }
    httpResp.status shouldBe HttpStatusCode.Found
    val inTimeCredentialOfferRequestUri = httpResp.headers[HttpHeaders.Location]!!
    CredentialOfferRequest.fromHttpQueryString(Url(inTimeCredentialOfferRequestUri).encodedQuery)
  }

  beforeSpec {
    ServiceMatrix("service-matrix.properties")
    credentialWallet = EBSITestWallet(CredentialWalletConfig("https://blank/"))
    ebsiClientConfig = OpenIDClientConfig(credentialWallet.TEST_DID, null, credentialWallet.config.redirectUri, useCodeChallenge = true)
  }

  /**
   * CTWalletCrossInTime, CTWalletSameInTime
   */
  "in-time credential".config(enabled = VcTestsEnabled) {
    forAll(
      row("CTWalletCrossInTime", crossDeviceCredentialOfferRequestCaller),
      row("CTWalletSameInTime", sameDeviceCredentialOfferRequestCaller),
    ) { credentialType, credentialOfferRequestCall ->
      val initCredentialOfferUrl =
        URLBuilder("https://api-conformance.ebsi.eu/conformance/v3/issuer-mock/initiate-credential-offer?credential_type=$credentialType").run {
          parameters.appendAll(StringValues.build {
            append("client_id", credentialWallet.TEST_DID)
            append("credential_offer_endpoint", "openid-credential-offer://")
          })
          build()
        }
      val credentialOfferRequest = credentialOfferRequestCall(initCredentialOfferUrl)
      val credentialOffer = credentialWallet.resolveCredentialOffer(credentialOfferRequest)
      val credentialResponses =
        credentialWallet.executeFullAuthIssuance(credentialOffer, credentialWallet.TEST_DID, ebsiClientConfig)
      credentialResponses.size shouldBe 1
      credentialResponses[0].isDeferred shouldBe false
      credentialResponses[0].credential shouldNotBe null
      storeCredentials(credentialResponses[0])
    }
  }

  /**
   * CTWalletCrossDeferred, CTWalletSameDeferred
   */
  "issue deferred credential".config(enabled = VcTestsEnabled) {
    forAll(
      row("CTWalletCrossDeferred", crossDeviceCredentialOfferRequestCaller),
      row("CTWalletSameDeferred", sameDeviceCredentialOfferRequestCaller),
    ) { credentialType, credentialOfferRequestCall ->
      val initCredentialOfferUrl =
        URLBuilder("https://api-conformance.ebsi.eu/conformance/v3/issuer-mock/initiate-credential-offer?credential_type=$credentialType").run {
          parameters.appendAll(StringValues.build {
            append("client_id", credentialWallet.TEST_DID)
            append("credential_offer_endpoint", "openid-credential-offer://")
          })
          build()
        }
      val deferredCredentialOfferRequest = credentialOfferRequestCall(initCredentialOfferUrl)
      val deferredCredentialOffer = credentialWallet.resolveCredentialOffer(deferredCredentialOfferRequest)
      val deferredCredentialResponses =
        credentialWallet.executeFullAuthIssuance(deferredCredentialOffer, credentialWallet.TEST_DID, ebsiClientConfig)
      deferredCredentialResponses.size shouldBe 1
      deferredCredentialResponses[0].isDeferred shouldBe true
      println("Waiting for deferred credential to be issued (5 seconds delay)")
      Thread.sleep(5500)
      println("Trying to fetch deferred credential")
      val credentialResponse =
        credentialWallet.fetchDeferredCredential(deferredCredentialOffer, deferredCredentialResponses[0])
      credentialResponse.isDeferred shouldBe false
      credentialResponse.isSuccess shouldBe true
      credentialResponse.credential shouldNotBe null
      storeCredentials(credentialResponse)
    }
  }

  /**
   * CTWalletCrossPreAuthorised, CTWalletSamePreAuthorised
   */
  "issue pre-authorized code credential".config(enabled = VcTestsEnabled) {
    forAll(
      row("CTWalletCrossPreAuthorised", crossDeviceCredentialOfferRequestCaller),
      row("CTWalletSamePreAuthorised", sameDeviceCredentialOfferRequestCaller),
    ) { credentialType, credentialOfferRequestCall ->
      val initCredentialOfferUrl =
        URLBuilder("https://api-conformance.ebsi.eu/conformance/v3/issuer-mock/initiate-credential-offer?credential_type=$credentialType").run {
          parameters.appendAll(StringValues.build {
            append("client_id", credentialWallet.TEST_DID)
            append("credential_offer_endpoint", "openid-credential-offer://")
          })
          build()
        }
      val preAuthCredentialOfferRequest = credentialOfferRequestCall(initCredentialOfferUrl)
      val preAuthCredentialOffer = credentialWallet.resolveCredentialOffer(preAuthCredentialOfferRequest)
      val preAuthCredentialResponses = credentialWallet.executePreAuthorizedCodeFlow(
        preAuthCredentialOffer, credentialWallet.TEST_DID, ebsiClientConfig, "3818"
      )
      preAuthCredentialResponses.size shouldBe 1
      preAuthCredentialResponses[0].isSuccess shouldBe true
      preAuthCredentialResponses[0].credential shouldNotBe null
      storeCredentials(preAuthCredentialResponses[0])
    }
  }

  /**
   * CTWalletQualificationCredential
   * Requires all VCs from above
   */
  "issue credential using presentation exchange".config(enabled = VpTestsEnabled) {
    val initIssuanceWithPresentationExchangeUrl = URLBuilder("https://api-conformance.ebsi.eu/conformance/v3/issuer-mock/initiate-credential-offer?credential_type=CTWalletQualificationCredential").run {
      parameters.appendAll(StringValues.build {
        append("client_id", credentialWallet.TEST_DID)
        append("credential_offer_endpoint", "openid-credential-offer://")
      })
      build()
    }
    val credentialOfferRequestUri = runBlocking { ktorClient.get(initIssuanceWithPresentationExchangeUrl).bodyAsText() }
    val credentialOfferRequest = CredentialOfferRequest.fromHttpQueryString(Url(credentialOfferRequestUri).encodedQuery)
    val credentialOffer = credentialWallet.resolveCredentialOffer(credentialOfferRequest)
    val credentialResponses = credentialWallet.executeFullAuthIssuance(credentialOffer, credentialWallet.TEST_DID, ebsiClientConfig)
    credentialResponses.size shouldBe 1
    credentialResponses[0].isDeferred shouldBe false
    credentialResponses[0].credential shouldNotBe null
  }
})

internal typealias credentialOfferRequestCaller = (initCredentialOfferUrl: Url) -> CredentialOfferRequest

internal fun storeCredentials(vararg credentialResponses: CredentialResponse) = credentialResponses.forEach {
  val cred = VerifiableCredential.fromString(it.credential!!.jsonPrimitive.content)
  Custodian.getService().storeCredential(cred.id ?: randomUUID(), cred)
}