package com.peterle95.watchnotetaker.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.peterle95.watchnotetaker.notes.MarkdownDeliveryTransport
import com.peterle95.watchnotetaker.notes.MarkdownConflictException
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.ReviewableNote
import com.peterle95.watchnotetaker.notes.markdownContent
import com.peterle95.watchnotetaker.notes.markdownFileName
import java.nio.charset.StandardCharsets.UTF_8

enum class VaultFolderState {
    CONNECTED,
    PERMISSION_REVOKED,
    UNAVAILABLE,
    NONE,
}

class VaultDelivery(
    private val context: Context,
) : MarkdownDeliveryTransport {
    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun select(uri: Uri) {
        releasePendingPermission()
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val previous = preferences.getString(URI_KEY, null)?.let(Uri::parse)
        val alreadyGranted = hasReadWriteGrant(uri)
        context.contentResolver.takePersistableUriPermission(uri, flags)
        try {
            check(hasReadWriteGrant(uri)) { "Vault folder permission was not persisted" }
            check(folder(uri)?.isUsable() == true) { "Vault folder is not readable and writable" }
            check(
                preferences.edit()
                    .putString(URI_KEY, uri.toString())
                    .putBoolean(REVOKED_KEY, false)
                    .commit(),
            ) { "Could not save vault folder" }
        } catch (failure: Exception) {
            if (!alreadyGranted) runCatching { context.contentResolver.releasePersistableUriPermission(uri, flags) }
            throw failure
        }
        if (previous != null && previous != uri) {
            runCatching { context.contentResolver.releasePersistableUriPermission(previous, flags) }
                .onFailure {
                    val pending = preferences.getStringSet(PENDING_RELEASE_KEY, emptySet()).orEmpty() + previous.toString()
                    preferences.edit().putStringSet(PENDING_RELEASE_KEY, pending).commit()
                }
        }
    }

    fun folderState(): VaultFolderState {
        releasePendingPermission()
        val uri = preferences.getString(URI_KEY, null)?.let(Uri::parse)
            ?: return if (preferences.getBoolean(REVOKED_KEY, false)) {
                VaultFolderState.PERMISSION_REVOKED
            } else {
                VaultFolderState.NONE
            }
        val granted = runCatching { hasReadWriteGrant(uri) }.getOrElse { return VaultFolderState.UNAVAILABLE }
        if (!granted) {
            check(
                preferences.edit().remove(URI_KEY).putBoolean(REVOKED_KEY, true).commit(),
            ) { "Could not clear revoked vault folder" }
            return VaultFolderState.PERMISSION_REVOKED
        }
        return if (folder(uri)?.isUsable() == true) VaultFolderState.CONNECTED else VaultFolderState.UNAVAILABLE
    }

    fun clear() {
        releasePendingPermission()
        val uri = preferences.getString(URI_KEY, null)?.let(Uri::parse)
        uri?.let {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            context.contentResolver.releasePersistableUriPermission(it, flags)
        }
        check(preferences.edit().remove(URI_KEY).putBoolean(REVOKED_KEY, false).commit()) {
            "Could not clear vault folder"
        }
    }

    private fun releasePendingPermission() {
        val pending = preferences.getStringSet(PENDING_RELEASE_KEY, emptySet()).orEmpty()
        if (pending.isEmpty()) return
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val remaining = pending.filterTo(mutableSetOf()) { value ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(value), flags) }.isFailure
        }
        preferences.edit().putStringSet(PENDING_RELEASE_KEY, remaining).commit()
    }

    override fun write(note: ReviewableNote) {
        require(note.status == NoteStatus.APPROVED) { "Only approved notes can be delivered" }
        val content = note.markdownContent()
        val vault = vault() ?: error("Select an Obsidian vault folder first")
        val name = note.markdownFileName()
        vault.findFile(name)?.let { existing ->
            existing.verify(content, note.id)
            return
        }
        vault.findFile("${note.id}.md")?.let { legacy ->
            legacy.verify(content, note.id)
            return
        }

        vault.listFiles()
            .filter { it.name?.startsWith(".${note.id}-") == true && it.name?.endsWith(".tmp") == true }
            .forEach { runCatching { it.delete() } }

        val temporary = vault.createFile("text/markdown", ".${note.id}-${System.nanoTime()}.tmp")
            ?: error("Could not create vault note")
        var renamed = false
        try {
            temporary.writeText(content)
            temporary.verify(content, note.id)
            renamed = temporary.renameTo(name)
            if (renamed) {
                val target = vault.findFile(name) ?: error("Could not verify finalized vault note")
                target.verify(content, note.id)
            } else {
                vault.findFile(name)?.let { existing ->
                    existing.verify(content, note.id)
                } ?: error("Could not finalize vault note")
            }
        } finally {
            if (!renamed) runCatching { temporary.delete() }
        }
    }

    private fun vault(): DocumentFile? {
        if (folderState() != VaultFolderState.CONNECTED) return null
        return preferences.getString(URI_KEY, null)
        ?.let(Uri::parse)
            ?.let(::folder)
    }

    private fun folder(uri: Uri): DocumentFile? = runCatching { DocumentFile.fromTreeUri(context, uri) }.getOrNull()

    private fun hasReadWriteGrant(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    private fun DocumentFile.isUsable(): Boolean = runCatching {
        exists() && isDirectory && canRead() && canWrite()
    }.getOrDefault(false)

    private fun DocumentFile.verify(content: String, noteId: String) {
        if (readText() != content) throw MarkdownConflictException("Vault already contains different content for $noteId")
    }

    private fun DocumentFile.readText(): String = context.contentResolver.openInputStream(uri)
        ?.bufferedReader(UTF_8)
        ?.use { it.readText() }
        ?: error("Could not read vault note")

    private fun DocumentFile.writeText(content: String) {
        context.contentResolver.openOutputStream(uri, "w")
            ?.bufferedWriter(UTF_8)
            ?.use { it.write(content) }
            ?: error("Could not write vault note")
    }

    companion object {
        private const val URI_KEY = "uri"
        private const val REVOKED_KEY = "permission-revoked"
        private const val PENDING_RELEASE_KEY = "pending-release-uri"
    }
}
