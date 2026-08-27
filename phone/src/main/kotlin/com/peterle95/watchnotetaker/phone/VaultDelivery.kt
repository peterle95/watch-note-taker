package com.peterle95.watchnotetaker.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.peterle95.watchnotetaker.notes.NoteStatus
import com.peterle95.watchnotetaker.notes.ReviewableNote
import java.nio.charset.StandardCharsets.UTF_8

class VaultDelivery(
    private val context: Context,
) {
    private val preferences = context.getSharedPreferences("vault", Context.MODE_PRIVATE)

    fun select(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        check(preferences.edit().putString("uri", uri.toString()).commit()) { "Could not save vault folder" }
    }

    fun isSelected(): Boolean = vault() != null

    fun clear() {
        preferences.edit().remove("uri").commit()
    }

    fun deliver(note: ReviewableNote) {
        require(note.status == NoteStatus.APPROVED) { "Only approved notes can be delivered" }
        val content = note.markdown()
        val vault = vault() ?: error("Select an Obsidian vault folder first")
        val name = "${note.id}.md"
        vault.findFile(name)?.let { existing ->
            require(existing.readText() == content) { "Vault already contains different content for ${note.id}" }
            return
        }

        val temporary = vault.createFile("text/markdown", ".${note.id}.tmp")
            ?: error("Could not create vault note")
        var finalized = false
        try {
            temporary.writeText(content)
            finalized = temporary.renameTo(name)
            if (!finalized) {
                vault.findFile(name)?.let { existing ->
                    require(existing.readText() == content) { "Vault already contains different content for ${note.id}" }
                } ?: error("Could not finalize vault note")
            }
        } finally {
            if (!finalized) temporary.delete()
        }
    }

    private fun vault(): DocumentFile? = preferences.getString("uri", null)
        ?.let(Uri::parse)
        ?.let { uri -> DocumentFile.fromTreeUri(context, uri) }

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

    private fun ReviewableNote.markdown(): String = buildString {
        appendLine("---")
        appendLine("created: $createdAt")
        appendLine("source: watch-note-taker")
        appendLine("status: approved")
        appendLine("---")
        appendLine()
        appendLine(transcript)
    }
}
