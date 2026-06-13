package com.otgmaster.exfat

class ExFatNode(
    val nodePtr: Long,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val createdAt: Long,
    val lastModified: Long
)
