#!/bin/sh
# Managerの終了後に、ダウンロードして検証済みのdmgでManagerの.appを置換し、再起動する。
#
# 使い方: update-macos.sh <プロセスID> <dmg> <.app> <結果ファイル> <版> <起動コマンド>
#
# dmgをマウントし、中の.appを現在の場所へコピーして置き換え、アンマウントする。
# Managerの設定とデータは.appの外（~/Library/Application Support）にあるため、退避は不要である。
# 置換は、新しい.appをコピーし終えてから入れ替える。失敗した場合は旧版のまま起動する。
#
# 結果は、成功と失敗のどちらでも<結果ファイル>へJSONで書き、Managerを再起動する。

PROCESS_ID="$1"
DMG="$2"
APP="$3"
RESULT_FILE="$4"
VERSION="$5"
LAUNCHER="${6:-open}"

SUCCESS=false
MESSAGE=""
MOUNT_DIR=""
STAGE=""
OLD=""

# JSONの文字列として書けるように、改行とタブを空白にして、バックスラッシュと引用符をエスケープする。
json_escape() {
    printf '%s' "$1" | tr '\n\r\t' '   ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

fail() {
    MESSAGE="$1"
}

cleanup() {
    if [ -n "$MOUNT_DIR" ]; then
        hdiutil detach "$MOUNT_DIR" -quiet >/dev/null 2>&1 || hdiutil detach "$MOUNT_DIR" -force -quiet >/dev/null 2>&1
        rmdir "$MOUNT_DIR" 2>/dev/null
    fi
    if [ -n "$STAGE" ] && [ -e "$STAGE" ]; then
        rm -rf "$STAGE"
    fi
}

update() {
    # Managerのプロセスが終了するまで待つ（最大60秒）。
    waited=0
    while kill -0 "$PROCESS_ID" 2>/dev/null; do
        if [ "$waited" -ge 300 ]; then
            fail "Managerが終了しなかったため、更新を中止しました。"
            return
        fi
        sleep 0.2
        waited=$((waited + 1))
    done

    if [ ! -f "$DMG" ]; then
        fail "更新用のdmgが見つかりません: $DMG"
        return
    fi

    MOUNT_DIR=$(mktemp -d "${TMPDIR:-/tmp}/SushiEricServerManager-update.XXXXXX") || {
        fail "マウント先を作成できませんでした。"
        MOUNT_DIR=""
        return
    }
    if ! output=$(hdiutil attach "$DMG" -nobrowse -readonly -noautoopen -mountpoint "$MOUNT_DIR" 2>&1); then
        fail "dmgをマウントできませんでした: $output"
        rmdir "$MOUNT_DIR" 2>/dev/null
        MOUNT_DIR=""
        return
    fi

    new_app=$(find "$MOUNT_DIR" -maxdepth 1 -name '*.app' -type d | head -n 1)
    if [ -z "$new_app" ]; then
        fail "dmgに.appが含まれていません。"
        return
    fi

    # 置換先と同じボリュームへコピーしてから入れ替えることで、コピーの失敗が旧版へ影響しないようにする。
    app_dir=$(dirname "$APP")
    app_name=$(basename "$APP")
    STAGE="$app_dir/.$app_name.new.$$"
    OLD="$app_dir/.$app_name.old.$$"

    if ! output=$(ditto "$new_app" "$STAGE" 2>&1); then
        fail "新しい.appをコピーできませんでした: $output"
        return
    fi
    if [ ! -d "$STAGE/Contents/MacOS" ]; then
        fail "コピーした.appが壊れています。"
        return
    fi

    if ! output=$(mv "$APP" "$OLD" 2>&1); then
        fail "旧版を退避できませんでした: $output"
        return
    fi
    if ! output=$(mv "$STAGE" "$APP" 2>&1); then
        # 新版を置けなかった場合は、旧版を戻す。
        mv "$OLD" "$APP" 2>/dev/null
        fail "新しい.appを置けませんでした: $output"
        return
    fi
    STAGE=""
    rm -rf "$OLD"
    SUCCESS=true
}

update
cleanup

if mkdir -p "$(dirname "$RESULT_FILE")" 2>/dev/null; then
    if [ -n "$MESSAGE" ]; then
        message_json="\"$(json_escape "$MESSAGE")\""
    else
        message_json="null"
    fi
    {
        printf '{\n'
        printf '  "success": %s,\n' "$SUCCESS"
        printf '  "version": "%s",\n' "$(json_escape "$VERSION")"
        printf '  "exitCode": null,\n'
        printf '  "message": %s,\n' "$message_json"
        printf '  "timestamp": "%s"\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        printf '}\n'
    } >"$RESULT_FILE" 2>/dev/null
fi

# 成功した場合は新版、失敗した場合は旧版のManagerを起動する。
if [ -d "$APP" ]; then
    "$LAUNCHER" "$APP"
fi
exit 0
