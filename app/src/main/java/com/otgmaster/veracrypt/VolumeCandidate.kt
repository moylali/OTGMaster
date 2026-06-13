package com.otgmaster.veracrypt

data class VolumeCandidate(
    val label: String,
    val startBlock: Long,
    val blockCount: Long?,
)
