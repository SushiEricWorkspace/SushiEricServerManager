package io.github.sushiericworkspace.sushiericservermanager.editor.view

/**
 * サーバー上のIDと、ローカルにだけ存在するIDを1つの並びへまとめます。
 *
 * サーバー側の並び順はそのまま保ち、サーバーへ未保存のIDを末尾へ名前順で追加します。
 * 未保存の新規データや複製データを、エディターの再オープン後もサイドバーから選べるようにするためです。
 *
 * @param remoteIds サーバー上のデータID。
 * @param cachedIds ローカルの編集キャッシュが持つデータID。
 * @return サーバー側のIDに続けて、サーバーに存在しないIDを名前順で並べた一覧。
 */
internal fun mergeSidebarIds(
    remoteIds: List<String>,
    cachedIds: Collection<String>
): List<String> {
    val remote = remoteIds.toSet()

    val localOnly = cachedIds
        .asSequence()
        .filterNot(remote::contains)
        .distinct()
        .sorted()
        .toList()

    return remoteIds + localOnly
}
