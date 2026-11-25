package app.fayaz.otgmaster.provider

import android.provider.DocumentsContract
import app.fayaz.otgmaster.core.DocumentHandle
import app.fayaz.otgmaster.core.EngineLog
import app.fayaz.otgmaster.extension.ExtensionContract

/**
 * OTG Master's DocumentsProvider: the manifest entry point under the store authority, kept by
 * name across the module split (the upgrade contract). The serving and the session safety are
 * [TreeDocumentsProvider]'s, in :saf-provider; this class adds OTG Master's policy: its mounted
 * drives ([OtgMounts]), read grants extended to "Open with" viewers and revoked on unmount, the
 * no-space toast, and the root's presentation.
 *
 * Its companion keeps the static API the app and the tests call, delegating to
 * [TreeDocumentsProvider]'s; touching it installs the registry and the failure hook, so any
 * caller that reaches the provider's state through this class sees OTG Master's drives.
 */
class VeraCryptDocumentProvider : TreeDocumentsProvider() {

    override val rootDocumentName: String get() = "VeraCrypt Drive"
    override val rootSummary: String get() = "Unlocked Volume"
    override val rootIcon: Int get() = app.fayaz.otgmaster.R.mipmap.ic_launcher
    override val fallbackAuthority: String get() = AUTHORITY

    override fun onCreate(): Boolean {
        appContext = context?.applicationContext
        return super.onCreate()
    }

    /**
     * Gives the calling app read access to [documentId]'s URI with any query string on it,
     * which is the same document it can already read.
     *
     * Google Drive's video viewer, opened from the Files app, re-requests the document as
     * ".../document/<id>?cpn=<nonce>". The grant the Files app gave it covers the URI without
     * the query, so Android refused the read before this provider ran ("Permission Denial ...
     * requires that you obtain access using ACTION_OPEN_DOCUMENT") and videos would not play,
     * while images, opened without a query, did. A prefix grant is matched on the path alone,
     * so it covers the query variants. Only for apps that reached the document without
     * MANAGE_DOCUMENTS (that is, through a grant), only read, only files — a prefix grant on
     * a directory would also cover its children listing — and revoked when the last drive
     * leaves ([revokeExtendedGrants]), or at the next start after a crash ([revokeLeftoverGrants]).
     */
    override fun onDocumentServed(documentId: String?) {
        val ctx = context ?: return
        val caller = callingPackage ?: return
        if (documentId == null || caller == ctx.packageName) return
        if (ctx.checkCallingPermission(android.Manifest.permission.MANAGE_DOCUMENTS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val uri = DocumentsContract.buildDocumentUri(AUTHORITY, documentId)
        if (!recordGrant(ctx, caller to uri)) return
        try {
            ctx.grantUriPermission(caller, uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        } catch (e: Exception) {
            forgetGrant(ctx, caller to uri)
            android.util.Log.w("OTGMaster", "could not extend read access for $caller: ${e.message}")
        }
    }

    companion object {
        const val AUTHORITY = ExtensionContract.AUTHORITY

        init {
            TreeDocumentsProvider.mounts = OtgMounts
            TreeDocumentsProvider.failureHook = { driveId, e ->
                if (app.fayaz.otgmaster.fs.NoSpace.isNoSpace(e)) reportNoSpace(driveId)
            }
        }

        // ── The static API, as before the split ──────────────────────────────────
        const val IDLE_SETTLE_MS = TreeDocumentsProvider.IDLE_SETTLE_MS
        fun rootIdForDrive(driveId: String): String = TreeDocumentsProvider.rootIdForDrive(driveId)
        fun rootDocIdForDrive(driveId: String): String = TreeDocumentsProvider.rootDocIdForDrive(driveId)
        fun wantsAvailableBytes(projection: Array<out String>?): Boolean = TreeDocumentsProvider.wantsAvailableBytes(projection)
        fun <T> onDrive(docId: String?, op: String = "request", body: () -> T): T = TreeDocumentsProvider.onDrive(docId, op, body)
        fun lockFor(docId: String?): java.util.concurrent.locks.ReentrantLock = TreeDocumentsProvider.lockFor(docId)
        fun driveInUse(driveId: String, settleMs: Long = IDLE_SETTLE_MS): Boolean = TreeDocumentsProvider.driveInUse(driveId, settleMs)
        fun awaitWriters(docId: String, waitMs: Long = 5000): Boolean = TreeDocumentsProvider.awaitWriters(docId, waitMs)
        fun openUnderLock(documentId: String?, write: Boolean, truncate: Boolean, resolve: () -> DocumentHandle?): TreeDocumentsProvider.Companion.OpenFile =
            TreeDocumentsProvider.openUnderLock(documentId, write, truncate, resolve)
        fun retire(fs: Any) = TreeDocumentsProvider.retire(fs)
        fun drainCallbacks(driveId: String? = null, timeoutSeconds: Long = 5) = TreeDocumentsProvider.drainCallbacks(driveId, timeoutSeconds)

        @Volatile private var appContext: android.content.Context? = null
        private val noSpaceReportedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

        /**
         * A save into [driveId] ran out of space: one plain line in the log and a toast,
         * at most every 10 s per drive, since the app saving the file (the Files app, a
         * gallery) shows only "couldn't copy". The technical reason is in the log line
         * logFailure wrote just before.
         */
        private fun reportNoSpace(driveId: String?) {
            val ctx = appContext ?: return
            val key = driveId ?: ""
            val now = android.os.SystemClock.elapsedRealtime()
            val last = noSpaceReportedAt[key]
            if (last != null && now - last < 10_000L) return
            noSpaceReportedAt[key] = now
            val name = driveId?.let { OtgMounts.find(it)?.name } ?: ctx.getString(app.fayaz.otgmaster.R.string.log_this_drive)
            val text = ctx.getString(app.fayaz.otgmaster.R.string.no_space_on_drive, name)
            EngineLog.log(text)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
            }
        }

        /**
         * Every (package, document URI) [extendReadGrant] granted, to revoke exactly those.
         * Also kept in [GRANTS_PREFS]: grants made with grantUriPermission outlive this
         * process until reboot, so after a crash or a kill [revokeLeftoverGrants] finds them.
         */
        private val extendedGrants: MutableSet<Pair<String, android.net.Uri>> =
            java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap())
        private const val GRANTS_PREFS = "extended_grants"

        private fun key(g: Pair<String, android.net.Uri>) = "${g.first} ${g.second}"

        private fun remember(context: android.content.Context, g: Pair<String, android.net.Uri>, keep: Boolean) {
            val prefs = context.getSharedPreferences(GRANTS_PREFS, android.content.Context.MODE_PRIVATE)
            if (keep) prefs.edit().putBoolean(key(g), true).apply() else prefs.edit().remove(key(g)).apply()
        }

        private fun revoke(context: android.content.Context, g: Pair<String, android.net.Uri>) {
            try {
                // The per-package form revokes only what this app granted with
                // grantUriPermission: grants other apps persisted via ACTION_OPEN_DOCUMENT stay.
                context.revokeUriPermission(g.first, g.second, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                android.util.Log.w("OTGMaster", "could not revoke read access for ${g.first}: ${e.message}")
            }
            extendedGrants.remove(g)
            remember(context, g, keep = false)
        }

        /**
         * Revokes the grants for documents on drives not in [mountedDriveIds]. Called on every
         * change to the mounted list, so a drive unmounted or unplugged loses its grants at
         * once while the others keep theirs.
         */
        fun revokeExtendedGrants(context: android.content.Context, mountedDriveIds: Set<String>) {
            for (g in extendedGrants.toList()) {
                val docId = runCatching { DocumentsContract.getDocumentId(g.second) }.getOrNull()
                if (docId == null || docId.substringBefore(':') !in mountedDriveIds) revoke(context, g)
            }
        }

        /** At start-up, before anything mounts: grants a previous process made and never revoked. */
        fun revokeLeftoverGrants(context: android.content.Context) {
            val prefs = context.getSharedPreferences(GRANTS_PREFS, android.content.Context.MODE_PRIVATE)
            for (k in prefs.all.keys) {
                val uri = runCatching { android.net.Uri.parse(k.substringAfter(' ')) }.getOrNull() ?: continue
                revoke(context, k.substringBefore(' ') to uri)
            }
            prefs.edit().clear().apply()
        }

        internal fun recordGrant(context: android.content.Context, g: Pair<String, android.net.Uri>): Boolean {
            if (!extendedGrants.add(g)) return false
            remember(context, g, keep = true)
            return true
        }

        internal fun forgetGrant(context: android.content.Context, g: Pair<String, android.net.Uri>) {
            extendedGrants.remove(g)
            remember(context, g, keep = false)
        }

    }
}
