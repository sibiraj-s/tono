package app.tono.audio

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Environment
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.tono.data.ToneUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class PickedContact(val id: Long, val name: String)

/** A contact with their own ringtone. [toneId] is set when it's a MediaStore tone (e.g. one of ours). */
data class ContactTone(
    val contactId: Long,
    val name: String,
    val toneUri: Uri,
    val toneTitle: String,
    val toneId: Long?,
)

/** A phone contact, for the multi-select contact picker. */
data class ContactEntry(
    val id: Long,
    val name: String,
    val starred: Boolean,
    val photoUri: String?,
    /** Their own ringtone (a URI string), or null when they use the phone's normal one. */
    val ringtone: String?,
)

/** A contact group (Family, Work…) or Favourites, merged across accounts by title. */
data class ContactGroup(val title: String, val memberIds: Set<Long>)

data class SavedTone(
    val uri: Uri,
    val id: Long,
    val title: String,
    val durationMs: Long,
    val use: ToneUse,
    val addedEpochSec: Long,
    /** Album art of the song it was cut from, when we know it. */
    val artUrl: String? = null,
)

/**
 * Saves tones into the shared Ringtones / Notifications / Alarms folders (under "Tono/")
 * through MediaStore, and sets them as the system default.
 *
 * Android 10+ lets an app read, update and delete the MediaStore entries it created without
 * any storage permission. Changing the default sound needs "Modify system settings".
 */
class ToneStore(private val context: Context) {

    private val resolver get() = context.contentResolver
    private val collection: Uri get() = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    fun canChangeSystemSounds(): Boolean = Settings.System.canWrite(context)

    fun manageWriteSettingsIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri())

    suspend fun save(file: File, title: String, use: ToneUse, artUrl: String? = null): Uri = withContext(Dispatchers.IO) {
        val safe = safeTitle(title)
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, "$safe.m4a")
            put(MediaStore.Audio.Media.TITLE, safe)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, folderFor(use))
            // Android 11+ derives which system picker lists the file from its folder; the flag is for older versions.
            put(use.flagColumn, 1)
            put(MediaStore.Audio.Media.IS_MUSIC, 0)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the tone file.")
        try {
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                ?: throw IOException("Couldn't write the tone file.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        if (artUrl != null) runCatching { setArt(ContentUris.parseId(uri), artUrl) }
        uri
    }

    /** A name not already used by another Tono tone: "Kaavaalaa", then "Kaavaalaa 2", "Kaavaalaa 3"… */
    suspend fun uniqueTitle(wanted: String, excludeId: Long? = null): String {
        val base = safeTitle(wanted)
        val taken = list().filter { it.id != excludeId }.map { it.title.lowercase() }.toSet()
        if (base.lowercase() !in taken) return base
        var n = 2
        while ("$base $n".lowercase() in taken) n++
        return "$base $n"
    }

    /**
     * Renames a tone. Android ignores title changes from apps once a file is saved, so this saves
     * a copy under the new name, moves its uses over (system sounds, contacts, album art), then
     * deletes the old file. Returns the renamed tone, which has a new id and URI.
     */
    suspend fun rename(tone: SavedTone, wanted: String): SavedTone = withContext(Dispatchers.IO) {
        val title = uniqueTitle(wanted, excludeId = tone.id)
        if (title == tone.title) return@withContext tone
        val usedAs = currentDefaultIds().filterValues { it == tone.id }.keys
        if (usedAs.isNotEmpty() && !canChangeSystemSounds()) {
            throw IOException("It’s your current ${usedAs.first().label.lowercase()}. Allow Tono to change system sounds, then rename it.")
        }
        // Without contacts access we can't see who uses it; contacts are only set through Tono with access granted.
        val contactIds = if (hasContactsPermission()) contactsWithCustomRingtones().filter { it.toneId == tone.id }.map { it.contactId } else emptyList()

        val tmp = File(context.cacheDir, "rename/${tone.id}.m4a").apply { parentFile?.mkdirs() }
        val newUri = try {
            resolver.openInputStream(tone.uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                ?: throw IOException("Couldn’t read that tone.")
            save(tmp, title, tone.use, readArt()[tone.id])
        } finally {
            tmp.delete()
        }
        try {
            usedAs.forEach { setAsDefault(newUri, it) }
            if (contactIds.isNotEmpty()) updateContactRingtones(contactIds, newUri, emptyList())
        } catch (e: Exception) {
            resolver.delete(newUri, null, null)
            throw e
        }
        resolver.delete(tone.uri, null, null)
        removeArt(tone.id)
        tone.copy(uri = newUri, id = ContentUris.parseId(newUri), title = title)
    }

    /** Requires [canChangeSystemSounds]. Also flags the file for that use so system pickers list it. */
    suspend fun setAsDefault(uri: Uri, use: ToneUse) = withContext(Dispatchers.IO) {
        resolver.update(uri, ContentValues().apply { put(use.flagColumn, 1) }, null, null)
        RingtoneManager.setActualDefaultRingtoneUri(context, use.ringtoneType, uri)
    }

    /** Ids of the current defaults, only when they're files on shared storage (where Tono's tones live). */
    fun currentDefaultIds(): Map<ToneUse, Long?> = ToneUse.entries.associateWith { use ->
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, use.ringtoneType)?.let(::externalMediaId) }.getOrNull()
    }

    /**
     * Deletes a tone and repoints anything using it, so the phone doesn't end up with a
     * missing sound: contacts go back to the normal ringtone, and a system default goes back
     * to a built-in sound. Returns what was changed, for the confirmation message.
     */
    suspend fun deleteTone(tone: SavedTone, usedAs: Set<ToneUse>, contactIds: List<Long>): List<String> = withContext(Dispatchers.IO) {
        // Never leave a system sound pointing at a deleted file: without the permission to
        // switch it back to a built-in sound, don't delete. (The screen asks for it first.)
        if (usedAs.isNotEmpty() && !canChangeSystemSounds()) {
            throw IOException("It’s your current ${usedAs.first().label.lowercase()}. Allow Tono to change system sounds, then delete it.")
        }
        val notes = mutableListOf<String>()
        if (contactIds.isNotEmpty() && hasContactsPermission()) {
            contactIds.forEach { runCatching { clearContactRingtone(it) } }
            notes += if (contactIds.size == 1) "1 contact uses your normal ringtone again" else "${contactIds.size} contacts use your normal ringtone again"
        }
        if (usedAs.isNotEmpty() && canChangeSystemSounds()) {
            usedAs.forEach { use ->
                builtInSound(use)?.let { fallback ->
                    runCatching { RingtoneManager.setActualDefaultRingtoneUri(context, use.ringtoneType, fallback) }
                        .onSuccess { notes += "your ${use.label.lowercase()} is back to a built-in sound" }
                }
            }
        }
        resolver.delete(tone.uri, null, null)
        runCatching { removeArt(tone.id) }
        notes
    }

    /** The first built-in (system) sound of that type, used as the fallback default. */
    private fun builtInSound(use: ToneUse): Uri? = runCatching {
        val rm = RingtoneManager(context).apply { setType(use.ringtoneType) }
        val c = rm.cursor
        var found: Uri? = null
        while (found == null && c.moveToNext()) {
            val u = rm.getRingtoneUri(c.position)
            if (u.authority == MediaStore.AUTHORITY && u.pathSegments.firstOrNull() == MediaStore.VOLUME_INTERNAL) found = u
        }
        found
    }.getOrNull()

    suspend fun list(): List<SavedTone> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.DATE_ADDED,
        )
        val tones = mutableListOf<SavedTone>()
        val art = readArt()
        resolver.query(
            collection,
            projection,
            "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
            arrayOf("%/$SUBFOLDER/"),
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val path = c.getString(pathCol).orEmpty()
                tones += SavedTone(
                    uri = ContentUris.withAppendedId(collection, id),
                    id = id,
                    title = c.getString(titleCol).orEmpty(),
                    durationMs = c.getLong(durCol),
                    use = when {
                        path.startsWith(Environment.DIRECTORY_ALARMS) -> ToneUse.Alarm
                        path.startsWith(Environment.DIRECTORY_NOTIFICATIONS) -> ToneUse.Notification
                        else -> ToneUse.Ringtone
                    },
                    addedEpochSec = c.getLong(addedCol),
                    artUrl = art[id],
                )
            }
        }
        tones
    }

    suspend fun delete(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.delete(uri, null, null)
        runCatching { removeArt(ContentUris.parseId(uri)) }
    }

    private fun safeTitle(title: String) =
        title.replace(Regex("""[\\/:*?"<>|]"""), " ").replace(Regex("""\s+"""), " ").trim().ifEmpty { "Tono tone" }

    private fun folderFor(use: ToneUse) = when (use) {
        ToneUse.Ringtone -> Environment.DIRECTORY_RINGTONES
        ToneUse.Notification -> Environment.DIRECTORY_NOTIFICATIONS
        ToneUse.Alarm -> Environment.DIRECTORY_ALARMS
    } + "/$SUBFOLDER/"

    private val ToneUse.flagColumn: String
        get() = when (this) {
            ToneUse.Ringtone -> MediaStore.Audio.Media.IS_RINGTONE
            ToneUse.Notification -> MediaStore.Audio.Media.IS_NOTIFICATION
            ToneUse.Alarm -> MediaStore.Audio.Media.IS_ALARM
        }

    // ---------- Per-contact ringtone ----------

    fun hasContactsPermission(): Boolean = CONTACTS_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    /** Reads the contact returned by the system contact picker (the picker grants read access to it). */
    suspend fun readContact(uri: Uri): PickedContact? = withContext(Dispatchers.IO) {
        resolver.query(uri, arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) PickedContact(c.getLong(0), c.getString(1) ?: "This contact") else null }
    }

    /** Requires [hasContactsPermission]. The Phone app plays this tone when that contact calls. */
    suspend fun setForContact(contactId: Long, tone: Uri) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put(ContactsContract.Contacts.CUSTOM_RINGTONE, tone.toString()) }
        val updated = resolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), values, null, null)
        if (updated == 0) throw IOException("Couldn't update that contact.")
    }

    /** Back to the phone's normal ringtone for this contact. */
    suspend fun clearContactRingtone(contactId: Long) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { putNull(ContactsContract.Contacts.CUSTOM_RINGTONE) }
        resolver.update(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, contactId), values, null, null)
    }

    /**
     * Gives every contact in [set] the ringtone [tone], and puts everyone in [clear] back on the
     * normal ringtone, in a few batched writes. Requires [hasContactsPermission].
     */
    suspend fun updateContactRingtones(set: Collection<Long>, tone: Uri?, clear: Collection<Long>) = withContext(Dispatchers.IO) {
        val ops = ArrayList<ContentProviderOperation>()
        fun op(id: Long, value: String?) = ContentProviderOperation
            .newUpdate(ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, id))
            .withValue(ContactsContract.Contacts.CUSTOM_RINGTONE, value)
            .build()
        if (tone != null) set.forEach { ops += op(it, tone.toString()) }
        clear.forEach { ops += op(it, null) }
        ops.chunked(200).forEach { resolver.applyBatch(ContactsContract.AUTHORITY, ArrayList(it)) }
    }

    /** Contacts with a phone number (the ones a ringtone matters for), A–Z. Requires [hasContactsPermission]. */
    suspend fun allContacts(): List<ContactEntry> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ContactEntry>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                ContactsContract.Contacts.STARRED,
                ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ContactsContract.Contacts.CUSTOM_RINGTONE,
            ),
            "${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                out += ContactEntry(
                    id = c.getLong(0),
                    name = c.getString(1)?.takeIf { it.isNotBlank() } ?: "Unnamed contact",
                    starred = c.getInt(2) == 1,
                    photoUri = c.getString(3),
                    ringtone = c.getString(4)?.takeIf { it.isNotBlank() },
                )
            }
        }
        out
    }

    /**
     * The user's contact groups with their members. Automatic groups ("My contacts") and the
     * account's own favourites group are skipped; Favourites comes from the starred flag instead.
     */
    suspend fun contactGroups(): List<ContactGroup> = withContext(Dispatchers.IO) {
        val titles = mutableMapOf<Long, String>()
        resolver.query(
            ContactsContract.Groups.CONTENT_URI,
            arrayOf(
                ContactsContract.Groups._ID, ContactsContract.Groups.TITLE,
                ContactsContract.Groups.AUTO_ADD, ContactsContract.Groups.FAVORITES,
            ),
            "${ContactsContract.Groups.DELETED} = 0",
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1)?.trim().orEmpty()
                if (title.isEmpty() || c.getInt(2) == 1 || c.getInt(3) == 1) continue
                titles[c.getLong(0)] = title
            }
        }
        if (titles.isEmpty()) return@withContext emptyList()
        val members = mutableMapOf<String, MutableSet<Long>>()
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.GroupMembership.GROUP_ROW_ID),
            "${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE),
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                val title = titles[c.getLong(1)] ?: continue
                members.getOrPut(title) { mutableSetOf() } += c.getLong(0)
            }
        }
        members.map { (title, ids) -> ContactGroup(title, ids) }.sortedBy { it.title.lowercase() }
    }

    /** Every contact that has its own ringtone, A–Z. Requires [hasContactsPermission]. */
    suspend fun contactsWithCustomRingtones(): List<ContactTone> = withContext(Dispatchers.IO) {
        val ours = list().associateBy { it.id }
        val out = mutableListOf<ContactTone>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.Contacts.CUSTOM_RINGTONE),
            "${ContactsContract.Contacts.CUSTOM_RINGTONE} IS NOT NULL AND ${ContactsContract.Contacts.CUSTOM_RINGTONE} != ''",
            null,
            "${ContactsContract.Contacts.DISPLAY_NAME} COLLATE NOCASE ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(2) ?: continue
                val uri = raw.toUri()
                val mediaId = externalMediaId(uri)
                val title = mediaId?.let { ours[it]?.title }
                    ?: uri.getQueryParameter("title")
                    ?: runCatching { RingtoneManager.getRingtone(context, uri)?.getTitle(context) }.getOrNull()
                    ?: "Custom ringtone"
                out += ContactTone(c.getLong(0), c.getString(1) ?: "Unnamed contact", uri, title, mediaId)
            }
        }
        out
    }

    // ---------- Album art per tone (MediaStore has no field for it) ----------

    private val artFile get() = File(context.filesDir, "tone_art.json")

    @Synchronized
    private fun readArt(): Map<Long, String> = runCatching {
        val json = org.json.JSONObject(artFile.readText())
        json.keys().asSequence().associate { it.toLong() to json.getString(it) }
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun setArt(id: Long, url: String) {
        val m = readArt().toMutableMap(); m[id] = url; writeArt(m)
    }

    @Synchronized
    private fun removeArt(id: Long) {
        val m = readArt().toMutableMap(); if (m.remove(id) != null) writeArt(m)
    }

    private fun writeArt(m: Map<Long, String>) {
        val json = org.json.JSONObject(); m.forEach { (k, v) -> json.put(k.toString(), v) }
        artFile.writeText(json.toString())
    }

    private val ToneUse.ringtoneType: Int
        get() = when (this) {
            ToneUse.Ringtone -> RingtoneManager.TYPE_RINGTONE
            ToneUse.Notification -> RingtoneManager.TYPE_NOTIFICATION
            ToneUse.Alarm -> RingtoneManager.TYPE_ALARM
        }

    companion object {
        const val SUBFOLDER = "Tono"

        /**
         * MediaStore id for a file on shared ("external") storage, else null. Built-in sounds live on
         * the "internal" volume and can share the same numbers, so they must not be matched by id alone.
         */
        fun externalMediaId(uri: Uri): Long? {
            if (uri.authority != MediaStore.AUTHORITY) return null
            val segs = uri.pathSegments
            if (segs.size < 2 || !segs[0].startsWith(MediaStore.VOLUME_EXTERNAL)) return null
            return segs.last().toLongOrNull()
        }

        /** Asked together; Android shows a single "Contacts" prompt. */
        val CONTACTS_PERMISSIONS = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS)
    }
}
