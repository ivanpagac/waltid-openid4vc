package id.walt.oid4vc.errors

import id.walt.oid4vc.data.CredentialOffer
import id.walt.oid4vc.requests.CredentialOfferRequest
import id.walt.oid4vc.requests.TokenRequest
import id.walt.oid4vc.responses.TokenErrorCode

class CredentialOfferError(
  val credentialOfferRequest: CredentialOfferRequest?, val credentialOffer: CredentialOffer?, val errorCode: CredentialOfferErrorCode, override val message: String? = null
): Exception() {
}

enum class CredentialOfferErrorCode {
  invalid_request,
  invalid_issuer
}