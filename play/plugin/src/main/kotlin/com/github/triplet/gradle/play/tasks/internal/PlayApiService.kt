package com.github.triplet.gradle.play.tasks.internal

import com.github.triplet.gradle.androidpublisher.EditManager
import com.github.triplet.gradle.androidpublisher.EditResponse
import com.github.triplet.gradle.androidpublisher.PlayPublisher
import com.github.triplet.gradle.common.utils.marked
import com.github.triplet.gradle.common.utils.nullOrFull
import com.github.triplet.gradle.common.utils.orNull
import com.github.triplet.gradle.common.utils.safeCreateNewFile
import com.github.triplet.gradle.play.internal.PlayExtensionConfig
import com.github.triplet.gradle.play.internal.credentialStream
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.logging.Logging
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import javax.inject.Inject

internal abstract class PlayApiService @Inject constructor(
        private val fileOps: FileSystemOperations,
) : BuildService<PlayApiService.Params> {
    val publisher by lazy {
        parameters.config.get().credentialStream().use {
            PlayPublisher(it, parameters.appId.get())
        }
    }
    val edits by lazy {
        val editId = getOrCreateEditId()
        editIdFile.safeCreateNewFile().writeText(editId)
        EditManager(publisher, editId)
    }

    private val editId get() = editIdFile.readText()
    private val editIdFile = parameters.editIdFile.get().asFile
    private val editIdFileAndFriends
        get() = listOf(editIdFile, editIdFile.marked("commit"), editIdFile.marked("skipped"))

    fun scheduleCommit() {
        editIdFile.marked("commit").safeCreateNewFile()
    }

    fun shouldCommit(): Boolean = editIdFile.marked("commit").exists()

    fun commit() {
        publisher.commitEdit(editId)
    }

    fun skipCommit() {
        editIdFile.marked("skipped").safeCreateNewFile()
    }

    fun shouldSkip(): Boolean = editIdFile.marked("skipped").exists()

    fun validate() {
        publisher.validateEdit(editId)
    }

    fun cleanup() {
        fileOps.delete { delete(editIdFileAndFriends) }
    }

    private fun getOrCreateEditId(): String {
        val editId = editIdFile.orNull()?.readText().nullOrFull()?.takeIf {
            editIdFile.marked("skipped").exists()
        }
        cleanup()

        val response = if (editId == null) {
            publisher.insertEdit()
        } else {
            editIdFile.marked("skipped").safeCreateNewFile()
            publisher.getEdit(editId)
        }

        return when (response) {
            is EditResponse.Success -> response.id
            is EditResponse.Failure -> handleFailure(response)
        }
    }

    private fun handleFailure(response: EditResponse.Failure): String {
        val appId = parameters.appId
        if (response.isNewApp()) {
            // Rethrow for clarity
            response.rethrow("""
                    |No application found for the package name '$appId'. The first version of your
                    |app must be uploaded via the Play Console.
                """.trimMargin())
        } else if (response.isInvalidEdit()) {
            Logging.getLogger(PlayApiService::class.java)
                    .error("Failed to retrieve saved edit, regenerating.")
            return getOrCreateEditId()
        } else if (response.isUnauthorized()) {
            response.rethrow("""
                    |Service account not authenticated. See the README for instructions:
                    |https://github.com/Triple-T/gradle-play-publisher#service-account
                """.trimMargin())
        } else {
            response.rethrow()
        }
    }

    interface Params : BuildServiceParameters {
        val config: Property<PlayExtensionConfig>
        val appId: Property<String>
        val editIdFile: RegularFileProperty
    }
}
