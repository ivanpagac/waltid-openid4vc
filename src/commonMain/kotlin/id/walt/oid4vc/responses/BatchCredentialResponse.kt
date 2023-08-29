package id.walt.oid4vc.responses

import id.walt.oid4vc.data.JsonDataObject
import id.walt.oid4vc.data.JsonDataObjectFactory
import id.walt.oid4vc.data.JsonDataObjectSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

@Serializable
data class BatchCredentialResponse private constructor(
  @SerialName("credential_responses") @Serializable(CredentialResponseListSerializer::class) val credentialResponses: List<CredentialResponse>?,
  @SerialName("c_nonce") val cNonce: String? = null,
  @SerialName("c_nonce_expires_in") val cNonceExpiresIn: Long? = null,
  val error: String? = null,
  @SerialName("error_description") val errorDescription: String? = null,
  @SerialName("error_uri") val errorUri: String? = null,
  override val customParameters: Map<String, JsonElement> = mapOf()
): JsonDataObject() {
  val isSuccess get() = credentialResponses != null
  override fun toJSON() = Json.encodeToJsonElement(BatchCredentialResponseSerializer, this).jsonObject

  companion object: JsonDataObjectFactory<BatchCredentialResponse>() {
    override fun fromJSON(jsonObject: JsonObject) = Json.decodeFromJsonElement(BatchCredentialResponseSerializer, jsonObject)

    fun success(credentialResponses: List<CredentialResponse>, cNonce: String? = null, cNonceExpiresIn: Long? = null)
        = BatchCredentialResponse(credentialResponses, cNonce, cNonceExpiresIn)
    fun error(error: CredentialErrorCode, errorDescription: String? = null, errorUri: String? = null, cNonce: String? = null, cNonceExpiresIn: Long? = null)
        = BatchCredentialResponse(
      null,
      error = error.name,
      errorDescription = errorDescription,
      errorUri = errorUri,
      cNonce = cNonce,
      cNonceExpiresIn = cNonceExpiresIn
    )

  }
}

object BatchCredentialResponseSerializer: JsonDataObjectSerializer<BatchCredentialResponse>(BatchCredentialResponse.serializer())
