package dev.loupe.kit.privacy

import dev.loupe.engine.ContentHash
import dev.loupe.sources.common.ItemKind
import dev.loupe.sources.common.SourceItem

/*
 * Exact duplicates, grouped by size and then content hash.
 *
 * PROVENANCE: ported from the owner's Loupe Station repository (`~/laya-studio`,
 * `laya_studio/scan/dupes.py` and the duplicates half of `laya_studio/scan/planner.py`, commit
 * ea7697a4f78e9a648ba49fc8bf9d13226c26cb0e). Same grouping (size, then content; groups of two or
 * more; files only, at least `min_size` bytes), same group id shape (`dup-` + 12 hex characters of a
 * hash of "<size>:<sorted member ids>"), same keep suggestion (the oldest, then the shortest path)
 * and the same `wasted_bytes`. The station hashes files in two passes (first 64 KiB, then all of
 * it) with BLAKE2b; here every item already carries the engine's SHA-256 `ContentHash` of its
 * whole content (computed by the scanner), which is the full-hash pass, so it is reused as is.
 */

/** One copy in a duplicate group. */
data class DuplicateMember(
    val itemId: String,
    val name: String,
    val location: String,
    /** "keep" for the suggested survivor, "trash_candidate" for the others. */
    val suggest: String,
) {
    val keep: Boolean get() = suggest == "keep"
}

/** A set of byte-identical files. */
data class DuplicateGroup(
    val groupId: String,
    val size: Long,
    val count: Int,
    val wastedBytes: Long,
    val keepItemId: String,
    val members: List<DuplicateMember>,
)

object Duplicates {
    /** Files smaller than this are never grouped (the station's `min_size` default). */
    const val MIN_SIZE: Long = 1

    /**
     * Whether [item] is a file for duplicate purposes: one message of a mail export, a calendar
     * event and a contact card share or have no file of their own.
     */
    fun isFile(item: SourceItem): Boolean =
        item.messageIndex == null && item.kind != ItemKind.EVENT && item.kind != ItemKind.CONTACT

    fun groups(items: List<SourceItem>, minSize: Long = MIN_SIZE): List<DuplicateGroup> {
        val bySize = items.filter { isFile(it) && it.sizeBytes >= minSize }.groupBy { it.sizeBytes }
        val out = mutableListOf<DuplicateGroup>()
        for ((size, same) in bySize) {
            if (same.size < 2) continue
            // One id is one file (a picked location read twice yields the same id): not a duplicate.
            val unique = same.distinctBy { it.id }
            for (members in unique.groupBy { it.contentHash }.values) {
                if (members.size < 2) continue
                val ids = members.map { it.id }.sorted()
                val gid = "dup-" + ContentHash.of("$size:" + ids.joinToString(",")).take(12)
                // The station keeps the oldest copy, then the shortest path.
                val keep = members.sortedWith(compareBy<SourceItem>({ it.date?.toEpochDays() ?: Int.MAX_VALUE }, { it.path.length }, { it.id })).first()
                out += DuplicateGroup(
                    groupId = gid, size = size, count = members.size, wastedBytes = size * (members.size - 1),
                    keepItemId = keep.id,
                    members = members.map {
                        DuplicateMember(it.id, it.fileName, NameHints.displayPath(it), if (it.id == keep.id) "keep" else "trash_candidate")
                    },
                )
            }
        }
        return out.sortedBy { it.groupId }
    }
}
