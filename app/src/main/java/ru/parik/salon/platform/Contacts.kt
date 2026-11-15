package ru.parik.salon.platform

import android.content.ContentResolver
import android.net.Uri
import android.provider.ContactsContract

// ACTION_PICK on Contacts.CONTENT_URI grants a temporary URI permission for this
// one contact. Query only that contact's entity directory; never the whole book.
data class SelectedContact(val name: String, val phones: List<String>)
object ContactReader {
    fun read(resolver: ContentResolver, uri: Uri): SelectedContact {
        require(uri.scheme == "content" && uri.authority == ContactsContract.AUTHORITY)
        var name = ""
        resolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0).orEmpty()
        }
        val phones = mutableListOf<String>()
        // The returned contact URI may be an /contacts/lookup/... URI. Its
        // entities subdirectory keeps the query within the granted contact.
        val entities = Uri.withAppendedPath(uri, ContactsContract.Contacts.Entity.CONTENT_DIRECTORY)
        resolver.query(entities, arrayOf(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(0) == ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE) {
                cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(phones::add)
            }
        }
        return SelectedContact(name, phones.distinct())
    }
    fun readSafely(resolver: ContentResolver, uri: Uri): SelectedContact {
        // Some OEMs grant only the base URI. Preserve the selected display name
        // and let the user enter a number if the entities query is forbidden.
        return try { read(resolver, uri) } catch (_: SecurityException) { nameOnly(resolver, uri) }
            catch (_: IllegalArgumentException) { nameOnly(resolver, uri) }
    }
    private fun nameOnly(resolver: ContentResolver, uri: Uri): SelectedContact {
        var name = ""
        runCatching {
            resolver.query(uri, arrayOf(ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0).orEmpty() }
        }
        return SelectedContact(name, emptyList())
    }
}
