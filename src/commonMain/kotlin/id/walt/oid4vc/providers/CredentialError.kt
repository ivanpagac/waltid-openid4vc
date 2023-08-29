package id.walt.oid4vc.providers

import id.walt.oid4vc.requests.BatchCredentialRequest
import id.walt.oid4vc.requests.CredentialRequest
import id.walt.oid4vc.responses.BatchCredentialResponse
import id.walt.oid4vc.responses.CredentialErrorCode
import id.walt.oid4vc.responses.CredentialResponse

class CredentialError(credentialRequest: CredentialRequest, val errorCode: CredentialErrorCode,
                      val errorUri: String? = null, val cNonce: String? = null, val cNonceExpiresIn: Long? = null,
                      override val message: String? = null): Exception() {
  fun toCredentialErrorResponse() = CredentialResponse.error(errorCode, message, errorUri, cNonce, cNonceExpiresIn)
}
class DeferredCredentialError(val errorCode: CredentialErrorCode, val errorUri: String? = null, val cNonce: String? = null, val cNonceExpiresIn: Long? = null,
                              override val message: String? = null): Exception() {
  fun toCredentialErrorResponse() = CredentialResponse.error(errorCode, message, errorUri, cNonce, cNonceExpiresIn)
}

class BatchCredentialError(batchCredentialRequest: BatchCredentialRequest, val errorCode: CredentialErrorCode, val errorUri: String? = null, val cNonce: String? = null, val cNonceExpiresIn: Long? = null,
                              override val message: String? = null): Exception() {
  fun toBatchCredentialErrorResponse() = BatchCredentialResponse.error(errorCode, message, errorUri, cNonce, cNonceExpiresIn)
}