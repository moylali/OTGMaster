/*
 * (C) Copyright 2014 mjahnen <github@mgns.tech>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */

package me.jahnen.libaums.core.fs.fat32

import android.util.Log
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * This class represents a cluster chain which can be followed in the FAT of a
 * FAT32 file system. You can [read][.read] from or
 * [write][.write] to it easily without having to worry
 * about the specific clusters.
 *
 * @author mjahnen
 */
internal class ClusterChain
/**
 * Constructs a new ClusterChain with the given information.
 *
 * @param startCluster
 * The start cluster which shall be followed in the FAT.
 * @param blockDevice
 * The block device where the fat fs is located.
 * @param fat
 * The file allocation table.
 * @param bootSector
 * The boot sector of the FAT32 fs.
 * @throws IOException
 */
@Throws(IOException::class)
internal constructor(startCluster: Long, private val blockDevice: BlockDeviceDriver, private val fat: FAT,
                     bootSector: Fat32BootSector) {
    private var chain: Array<Long>
    private val clusterSize: Long
    private val dataAreaOffset: Long

    /**
     * The chain's current first cluster, or 0 when the chain is empty.
     *
     * LOCAL PATCH (docs/VENDOR_FIXES.md V2): growing or shrinking replaces the whole
     * chain array, so the first cluster can change. FatFile has to write that back
     * into its directory entry, and could not see it while `chain` was private.
     */
    internal val firstCluster: Long
        get() = if (chain.isEmpty()) 0L else chain[0]

    /**
     * Gets the current allocated clusters for this chain.
     *
     * @return The number of clusters.
     * @see .setClusters
     * @see .getLength
     */

    /**
     * Sets a new cluster size for the cluster chain. This method allocates or
     * frees clusters in the FAT if the number of the new clusters is bigger or
     * lower than the current number of allocated clusters.
     * @see .getClusters
     * @see .setLength
     */
    private var clusters: Int
        get() = chain.size
        @Throws(IOException::class)
        set(newNumberOfClusters) {
            val oldNumberOfClusters = clusters
            if (newNumberOfClusters == oldNumberOfClusters)
                return

            chain = if (newNumberOfClusters > oldNumberOfClusters) {
                Log.d(TAG, "grow chain")
                fat.alloc(chain, newNumberOfClusters - oldNumberOfClusters)
            } else {
                Log.d(TAG, "shrink chain")
                fat.free(chain, oldNumberOfClusters - newNumberOfClusters)
            }
        }

    /**
     * Returns or sets the size in bytes the chain currently occupies on the disk.
     *
     * @throws IOException
     * If growing or allocating the chain fails.
     * @see .getLength
     * @see .setClusters
     */
     internal var length: Long
        get() = chain.size * clusterSize
        @Throws(IOException::class)
        set(newLength) {
            val newNumberOfClusters = (newLength + clusterSize - 1) / clusterSize
            clusters = newNumberOfClusters.toInt()
        }

    init {
        Log.d(TAG, "Init a cluster chain, reading from FAT")
        chain = fat.getChain(startCluster)
        clusterSize = bootSector.bytesPerCluster.toLong()
        dataAreaOffset = bootSector.dataAreaOffset
        Log.d(TAG, "Finished init of a cluster chain")
    }

    /**
     * Reads from the cluster chain at the given offset into the given buffer.
     * This method automatically searches for following clusters in the chain
     * and reads from them appropriately.
     *
     * @param offset
     * The offset in bytes where reading shall start.
     * @param dest
     * The destination buffer the contents of the chain shall be
     * copied to.
     * @throws IOException
     * If reading fails.
     */
     @Throws(IOException::class)
    internal fun read(offset: Long, dest: ByteBuffer) {
        var length = dest.remaining()

        var chainIndex = (offset / clusterSize).toInt()
        // if the offset is not a multiple of the cluster size we have to start
        // reading
        // directly in the cluster
        if (offset % clusterSize != 0L) {
            // offset in the cluster
            val clusterOffset = (offset % clusterSize).toInt()
            val size = Math.min(length, (clusterSize - clusterOffset).toInt())
            dest.limit(dest.position() + size)

            blockDevice.read(getFileSystemOffset(chain[chainIndex], clusterOffset), dest)

            // round up to next cluster in the chain
            chainIndex++
            // make length now a multiple of the cluster size
            length -= size
        }

        // LOCAL PATCH (docs/VENDOR_FIXES.md V6): coalesce consecutive clusters, as
        // write() already does.
        //
        // This loop used to issue one read per cluster unconditionally, so a 512 KiB
        // read at 4 KiB clusters became 128 separate SCSI commands. The block layer
        // measures ~3.2 MB/s at 4 KiB spans against ~19.6 MB/s at 512 KiB, so the
        // fragmentation, not the bus, was the limit on FAT32 sequential reads.
        //
        // Only physically consecutive clusters can go in one transfer, which is why
        // the chain is scanned first — the same reasoning and the same scan as
        // write(). The cap is higher than write()'s 4 because LibaumsRawBlockDevice
        // now splits anything oversized into 120 KiB transfers of its own, so a large
        // span here can no longer reach the USB stack as one outsized request.
        while (length > 0) {
            var consecutive = 1
            for (i in chainIndex until chain.size - 1) {
                if (chain[i] + 1 == chain[i + 1]) consecutive++ else break
            }
            consecutive = Math.min(consecutive, MAX_READ_CLUSTERS)

            val clustersLeft = ((length + clusterSize - 1) / clusterSize).toInt()
            val clusters = Math.min(consecutive, clustersLeft)
            val size = Math.min(clusterSize * clusters, length.toLong()).toInt()
            dest.limit(dest.position() + size)

            blockDevice.read(getFileSystemOffset(chain[chainIndex], 0), dest)

            chainIndex += clusters
            length -= size
        }
    }

    /**
     * Writes to the cluster chain at the given offset from the given buffer.
     * This method automatically searches for following clusters in the chain
     * and reads from them appropriately.
     *
     * @param offset
     * The offset in bytes where writing shall start.
     * @param source
     * The buffer which holds the contents which shall be transferred
     * into the cluster chain.
     * @throws IOException
     * If writing fails.
     */
     @Throws(IOException::class)
    internal fun write(offset: Long, source: ByteBuffer) {
        var length = source.remaining()

        var chainIndex = (offset / clusterSize).toInt()
        // if the offset is not a multiple of the cluster size we have to start
        // reading
        // directly in the cluster
        if (offset % clusterSize != 0L) {
            val clusterOffset = (offset % clusterSize).toInt()
            val size = min(length, (clusterSize - clusterOffset).toInt())
            source.limit(source.position() + size)

            blockDevice.write(getFileSystemOffset(chain[chainIndex], clusterOffset), source)

            // round up to next cluster in the chain
            chainIndex++
            // make length now a multiple of the cluster size
            length -= size
        }

        var remainingClusters = length / clusterSize

        // now we can proceed reading the clusters without an offset in the
        // cluster
        while (length > 0) {
            val size: Int
            var numberOfClusters = 1

            // We can only write consecutive clusters, see tests failing in
            // https://github.com/magnusja/libaums/pull/236/commits/a4cfe0c57401f922beec849e706b68d94cad3248
            var maxConsecutiveClusters = 1
            for (i in chainIndex until chain.size - 1) {
                if (chain[i] + 1 == chain[i + 1]) {
                    maxConsecutiveClusters++
                } else {
                    break
                }
            }
            // we write multiple clusters at a time, to speed up the write performance enormously
            // currently only 4 or fewer clusters are written at the same time. Set this value too high may cause problems
            maxConsecutiveClusters = min(maxConsecutiveClusters, 4)
            when {
                remainingClusters > maxConsecutiveClusters -> {
                    size = (clusterSize * maxConsecutiveClusters).toInt()
                    numberOfClusters = maxConsecutiveClusters
                    remainingClusters -= maxConsecutiveClusters
                }
                remainingClusters > 0 -> {
                    size = (clusterSize * min(remainingClusters.toInt(), maxConsecutiveClusters)).toInt()
                    numberOfClusters = min(remainingClusters.toInt(), maxConsecutiveClusters)
                    remainingClusters -= numberOfClusters
                }
                else -> size = length
            }

            source.limit(source.position() + size)

            blockDevice.write(getFileSystemOffset(chain[chainIndex], 0), source)

            chainIndex += numberOfClusters
            length -= size
        }
    }

    /**
     * Returns the offset of a cluster from the beginning of the FAT32 file
     * system in bytes.
     *
     * @param cluster
     * The desired cluster.
     * @param clusterOffset
     * The desired offset in bytes in the cluster.
     * @return Offset in bytes from the beginning of the disk (FAT32 file
     * system).
     */
    private fun getFileSystemOffset(cluster: Long, clusterOffset: Int): Long {
        return dataAreaOffset + clusterOffset.toLong() + (cluster - 2) * clusterSize
    }

    companion object {
        /**
         * Clusters coalesced into one read at most. 32 is 128 KiB at 4 KiB clusters,
         * past the knee of the block layer's size/throughput curve (~3.2 MB/s at
         * 4 KiB, ~14 MB/s at 64 KiB, ~19.6 MB/s at 512 KiB).
         */
        private const val MAX_READ_CLUSTERS = 32


        private val TAG = ClusterChain::class.java.simpleName
    }
}
