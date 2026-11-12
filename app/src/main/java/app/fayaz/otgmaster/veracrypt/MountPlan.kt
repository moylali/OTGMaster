package app.fayaz.otgmaster.veracrypt

/**
 * Which VeraCrypt headers a one-password mount request tries, after it has tried every
 * candidate's standard header.
 */
object MountPlan {

    /**
     * The candidates whose hidden header is tried next: every VeraCrypt candidate still locked
     * after the standard pass, whatever else opened, unless [hiddenHeaders] is false.
     *
     * Review of #141 (PR-4): the hidden pass used to run only if no candidate at all had
     * mounted, so one partition opening with this password stopped another partition's hidden
     * volume, which the same password opens, from ever being tried; and a LUKS or BitLocker
     * candidate could do the same. A caller that wants standard headers only (the benchmark,
     * whose timing a background hidden pass would disturb) says so with [hiddenHeaders].
     */
    fun <D> hiddenPass(
        requested: List<Pair<D, VolumeCandidate>>,
        hiddenHeaders: Boolean,
        stillLocked: (D, VolumeCandidate) -> Boolean,
    ): List<Pair<D, VolumeCandidate>> =
        if (!hiddenHeaders) emptyList()
        else requested.filter { (d, c) -> c.containerType == ContainerType.VERACRYPT && stillLocked(d, c) }
}
