package io.github.sushiericworkspace.sushiericservermanager.editor.merge

data class DataConflict(
    val path: DataFieldPath,
    val displayName: String,
    val baseValue: Any?,
    val localValue: Any?,
    val remoteValue: Any?
)

class ThreeWayMergeResult<T> internal constructor(
    val merged: T,
    val conflicts: List<DataConflict>,
    private val copier: (T) -> T,
    private val localResolvers: Map<DataFieldPath, (T) -> Unit>
) {
    fun resolveWithLocal(localPaths: Set<DataFieldPath>): T {
        val resolved = copier(merged)
        localPaths
            .sortedWith(
                compareByDescending<DataFieldPath> {
                    (it.segments.lastOrNull() as? DataFieldSegment.Index)?.value ?: -1
                }
            )
            .forEach { path ->
                localResolvers[path]?.invoke(resolved)
            }
        return resolved
    }
}

interface DataMerger<T> {
    fun merge(base: T, local: T, remote: T): ThreeWayMergeResult<T>
}

internal class MergeAccumulator<T>(
    private val merged: T,
    private val copier: (T) -> T
) {
    private val conflicts = mutableListOf<DataConflict>()
    private val localResolvers = linkedMapOf<DataFieldPath, (T) -> Unit>()

    fun <V> mergeValue(
        path: DataFieldPath,
        base: V,
        local: V,
        remote: V,
        applyLocal: (T, V) -> Unit
    ) {
        val localChanged = local != base
        val remoteChanged = remote != base

        when {
            !localChanged -> Unit
            !remoteChanged || local == remote -> applyLocal(merged, local)
            else -> {
                conflicts += DataConflict(
                    path = path,
                    displayName = path.displayName,
                    baseValue = base,
                    localValue = local,
                    remoteValue = remote
                )
                localResolvers[path] = { target -> applyLocal(target, local) }
            }
        }
    }

    fun <K, V> mergeMap(
        path: DataFieldPath,
        base: Map<K, V>,
        local: Map<K, V>,
        remote: Map<K, V>,
        keyDisplay: (K) -> String = { it.toString() },
        copyValue: (V) -> V = { it },
        targetMap: (T) -> MutableMap<K, V>
    ) {
        val keys = base.keys + local.keys + remote.keys
        keys.forEach { key ->
            val fieldPath = path.key(key as Any, keyDisplay(key))
            mergeValue(
                path = fieldPath,
                base = EntryValue.of(base, key),
                local = EntryValue.of(local, key),
                remote = EntryValue.of(remote, key)
            ) { target, value ->
                val map = targetMap(target)
                when (value) {
                    EntryValue.Missing -> map.remove(key)
                    is EntryValue.Present -> map[key] = copyValue(value.value)
                }
            }
        }
    }

    fun <V> mergeList(
        path: DataFieldPath,
        base: List<V>,
        local: List<V>,
        remote: List<V>,
        copyValue: (V) -> V = { it },
        indexDisplay: (Int) -> String = { "${it + 1}番目" },
        targetList: (T) -> MutableList<V>
    ) {
        if (local == base) return
        if (remote == base || local == remote) {
            val replacement = local.map(copyValue)
            val target = targetList(merged)
            target.clear()
            target.addAll(replacement)
            return
        }

        val maxSize = maxOf(base.size, local.size, remote.size)
        for (index in 0 until maxSize) {
            val fieldPath = path.index(index, indexDisplay(index))
            mergeValue(
                path = fieldPath,
                base = IndexValue.of(base, index),
                local = IndexValue.of(local, index),
                remote = IndexValue.of(remote, index)
            ) { target, value ->
                val list = targetList(target)
                when (value) {
                    IndexValue.Missing -> if (index < list.size) list.removeAt(index)
                    is IndexValue.Present -> {
                        val copied = copyValue(value.value)
                        if (index < list.size) {
                            list[index] = copied
                        } else {
                            list.add(copied)
                        }
                    }
                }
            }
        }
    }

    fun result(): ThreeWayMergeResult<T> {
        return ThreeWayMergeResult(
            merged = merged,
            conflicts = conflicts.toList(),
            copier = copier,
            localResolvers = localResolvers.toMap()
        )
    }

    internal sealed interface EntryValue<out V> {
        data object Missing : EntryValue<Nothing>
        data class Present<V>(val value: V) : EntryValue<V>

        companion object {
            fun <K, V> of(map: Map<K, V>, key: K): EntryValue<V> {
                return if (map.containsKey(key)) Present(map.getValue(key)) else Missing
            }
        }
    }

    internal sealed interface IndexValue<out V> {
        data object Missing : IndexValue<Nothing>
        data class Present<V>(val value: V) : IndexValue<V>

        companion object {
            fun <V> of(list: List<V>, index: Int): IndexValue<V> {
                return list.getOrNull(index)?.let(::Present) ?: Missing
            }
        }
    }
}
