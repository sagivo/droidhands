package dev.phonebridge.core.auth

import java.io.File

/** STUB — implemented by the core agent. */
class InMemoryTokenStore(initial: List<TokenRecord> = emptyList()) : TokenStore {
    override fun load(): List<TokenRecord> = TODO()
    override fun save(tokens: List<TokenRecord>): Unit = TODO()
}

/** JSON file store with atomic replace (write temp + rename). STUB — implemented by the core agent. */
class FileTokenStore(private val file: File) : TokenStore {
    override fun load(): List<TokenRecord> = TODO()
    override fun save(tokens: List<TokenRecord>): Unit = TODO()
}
