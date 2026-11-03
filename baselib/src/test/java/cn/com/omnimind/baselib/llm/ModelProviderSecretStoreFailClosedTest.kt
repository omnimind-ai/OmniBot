package cn.com.omnimind.baselib.llm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ModelProviderSecretStoreFailClosedTest {
    @Test
    fun readFailureDisablesByokSecretsWithoutPropagatingKeystoreException() {
        val store = FailClosedModelProviderSecretStore(ThrowingSecretStore())

        assertNull(store.readProfile("profile-1"))
        assertFalse(store.isAvailable())
        assertThrows(IllegalStateException::class.java) {
            store.writeProfile("profile-1", ModelProviderSecrets(apiKey = "replacement"))
        }
    }

    @Test
    fun unavailableStoreReturnsNoCredentialAndRejectsWrites() {
        val store = FailClosedModelProviderSecretStore(null)

        assertNull(store.readLegacy("legacy"))
        assertFalse(store.isAvailable())
        assertThrows(IllegalStateException::class.java) {
            store.writeLegacy("legacy", "replacement")
        }
    }

    @Test
    fun writeFailureDisablesSecretsAndRejectsFurtherMutations() {
        val delegate = object : ModelProviderSecretStore by ThrowingSecretStore() {
            override fun readProfile(profileId: String) = ModelProviderSecrets(apiKey = "existing")

            override fun writeProfile(profileId: String, secrets: ModelProviderSecrets) {
                error("simulated encrypted write failure")
            }
        }
        val store = FailClosedModelProviderSecretStore(delegate)

        assertThrows(IllegalStateException::class.java) {
            store.writeProfile("profile-1", ModelProviderSecrets(apiKey = "replacement"))
        }

        assertFalse(store.isAvailable())
        assertNull(store.readProfile("profile-1"))
        assertThrows(IllegalStateException::class.java) {
            store.deleteProfile("profile-1")
        }
    }
}

private class ThrowingSecretStore : ModelProviderSecretStore {
    override fun isAvailable(): Boolean = true

    override fun readProfile(profileId: String): ModelProviderSecrets? =
        error("simulated encrypted storage failure")

    override fun writeProfile(profileId: String, secrets: ModelProviderSecrets) = Unit

    override fun deleteProfile(profileId: String) = Unit

    override fun deleteProfilesExcept(profileIds: Set<String>) = Unit

    override fun readLegacy(storageKey: String): String? =
        error("simulated encrypted storage failure")

    override fun writeLegacy(storageKey: String, apiKey: String) = Unit

    override fun deleteLegacy(storageKey: String) = Unit
}
