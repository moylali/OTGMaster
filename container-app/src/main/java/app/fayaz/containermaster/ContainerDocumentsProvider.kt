package app.fayaz.containermaster

import app.fayaz.otgmaster.provider.TreeDocumentsProvider

/**
 * Container Master's DocumentsProvider, under its own authority (app.fayaz.containermaster.documents).
 * The serving and the session safety are :saf-provider's; this class supplies the open containers
 * ([ContainerSessions]) and the roots' presentation. Read-only: every container is mounted so.
 */
class ContainerDocumentsProvider : TreeDocumentsProvider() {

    override val rootDocumentName: String get() = "Container"
    override val rootSummary: String
        get() = context?.getString(R.string.root_summary) ?: "Unlocked container, read-only"
    override val rootIcon: Int get() = android.R.drawable.ic_lock_lock
    override val fallbackAuthority: String get() = "app.fayaz.containermaster.documents"

    companion object {
        init {
            TreeDocumentsProvider.mounts = ContainerSessions
        }
    }
}
