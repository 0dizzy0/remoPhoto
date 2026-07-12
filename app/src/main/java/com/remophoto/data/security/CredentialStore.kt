package com.remophoto.data.security

/** 凭据存储契约；调用方负责在使用后清零返回的字符数组。 */
interface CredentialStore {
    fun storeCredential(connectionId: Long, credential: CharArray)
    fun getCredential(connectionId: Long): CharArray?
    fun deleteCredential(connectionId: Long)
    fun hasCredential(connectionId: Long): Boolean
}
