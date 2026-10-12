"""形式1のプロファイル・引継ぎを正本ライブラリへ委譲するManager専用入口。"""
import json
from pathlib import Path
import socket
import sys
import time


def execute(request, *, lease_check=None):
    cli = Path(request["cli"])
    sys.path.insert(0, str(cli.parent))
    from dev_server.connection_profiles import ConnectionStore, local_request, profile_hash, validate_handoff, check_handoff
    from dev_server.local_ipc import decode, encode, require_private
    from dev_server.registry import RegistryError, require, identifier
    from dev_server.build_lock import build_lock
    from dev_server import storage
    require(sys.version_info[:2] == (3, 12), "UNAVAILABLE", "Python 3.12が必要です")
    store = ConnectionStore(Path(request["directory"]))
    profile = store.profile(request["profileId"])
    require(profile == request["expectedProfile"], "PROFILE_CHANGED", "固定プロファイルが変わっています")
    if profile["transport"] == "local":
        require(profile["targetOs"] == {"win32": "windows", "darwin": "macos"}.get(sys.platform),
                "UNAVAILABLE", "対象OSが一致しません")
        require(profile["host"].casefold() == socket.gethostname().casefold(), "UNAVAILABLE", "対象ホストが一致しません")
        require(Path(profile["python"]).resolve() == Path(sys.executable).resolve() and Path(profile["cli"]).resolve() == cli.resolve(),
                "PROFILE_CHANGED", "PythonまたはCLIが一致しません")
    else:
        require(lease_check is not None, "UNAVAILABLE", "SSH仲介の検査が必要です")
    command = request["command"]
    def pending_manager_operations():
        directory = store.directory / "manager-operations"
        if not directory.exists(): return False
        require_private(directory, directory=True)
        records = []
        partial = False
        for path in directory.iterdir():
            require_private(path)
            require(path.is_file() and path.stat().st_nlink == 1 and path.stat().st_size <= 65536,
                    "INVALID_INPUT", "独立したprivate操作記録が必要です")
            if path.suffix != ".json":
                partial = True
                continue
            record = decode(path.read_bytes())
            if record.get("profileId") == profile["profileId"]: records.append(record)
        acquired = {record["result"]["leaseId"] for record in records if record["state"] == "COMPLETED" and record["command"] == "acquire"}
        released = {record["payload"]["leaseId"] for record in records if record["state"] == "COMPLETED" and record["command"] == "release"}
        return partial or any(record["state"] != "COMPLETED" for record in records) or bool(acquired - released)

    if command == "profile":
        with build_lock(store.directory / "handoffs.lock"):
            return {"profile": profile, "pendingManagerOperation": pending_manager_operations()}
    if command == "offers":
        candidates = {row["instanceId"] for row in profile["instances"]}
        offered = []
        with build_lock(store.directory / "handoffs.lock"):
            for path in sorted((store.directory / "handoffs").iterdir()):
                # 不完全な記録は上書きせず、診断対象として拒否します。
                record = store._read(path)
                validate_handoff(record)
                require(path.name == record["handoffId"] + ".json", "INVALID_INPUT", "引継ぎ記録のIDが一致しません")
                if (record["profileHash"] == profile_hash(profile) and record["targetClient"] == "manager"
                        and record["state"] == "OFFERED" and record["expiresAt"] > time.time()
                        and record["token"]["instanceId"] in candidates):
                    offered.append({key: record[key] for key in ("handoffId", "owner", "sourceClient", "expiresAt", "runId", "nonce")}
                                   | {key: record["token"][key] for key in ("instanceId", "epoch", "expectedGeneration")})
        return {"offers": offered}
    if command == "reoffer":
        operation = identifier(request["operationId"], "operationId")
        with build_lock(store.directory / "handoffs.lock"):
            journal = store._read(store.directory / "manager-operations" / (operation + ".json"))
            payload = {key: request[key] for key in ("handoffId", "owner")}
            require(journal["profileHash"] == profile_hash(profile) and journal["command"] == "reconnect"
                    and journal["state"] == "UNKNOWN" and journal["payload"] == payload and not journal.get("reofferStarted"),
                    "JOURNAL_MISMATCH", "送信前の再接続記録が必要です")
            record = store._read(store._path("handoffs", request["handoffId"]))
            validate_handoff(record)
            require(record["handoffId"] == request["handoffId"] and record["state"] == "CLAIMED",
                    "HANDOFF_PENDING", "取得済み引継ぎだけを再発行できます")
            require(record["profileHash"] == profile_hash(profile) and record["owner"] == request["owner"]
                    and record["targetClient"] == "manager", "OWNER_MISMATCH", "同じプロファイル・ownerの引継ぎが必要です")
            journal["reofferStarted"] = True
            store._write(store.directory / "manager-operations" / (operation + ".json"), journal, replace=True)
        token = dict(record["token"], owner=record["owner"])
        checked = lease_check(token) if lease_check is not None else local_request(profile, "lease-check", token, operation_id=operation)
        # claim期限を再使用せず、同じ貸出の現在期限と正常終了済みwriterを照合する。
        check_handoff(profile, dict(record, expiresAt=checked["expiresAt"]), checked,
                      owner=record["owner"], target_client="manager", now=time.time())
        require(bool(checked["writerSessions"]) and all(row["status"] == "CLOSED" for row in checked["writerSessions"]),
                "WRITER_PENDING", "writerの正常終了を確認してから再接続してください")
        handoff = store.offer(profile, record["token"], checked, owner=record["owner"],
                              source_client=record["sourceClient"], target_client="manager")
        return {"handoffId": handoff}
    if command == "claim":
        checked = None
        def check_lease(payload):
            nonlocal checked
            checked = lease_check(payload) if lease_check is not None else local_request(profile, "lease-check", payload, operation_id=request["operationId"])
            return checked
        record = store.claim(profile, request["handoffId"], owner=request["owner"], target_client=request["targetClient"], lease_check=check_lease)
        # claim期限と、発行元が更新した接続中の現在期限を分離します。
        return {"handoff": record, "checked": checked}
    require(command in {"journal", "finish"}, "INVALID_INPUT", "未対応の入口です")
    identifier(request["operationId"], "operationId")
    directory = store.directory / "manager-operations"
    if not directory.exists(): storage.mkdir(directory)
    require_private(directory, directory=True)
    path = directory / (request["operationId"] + ".json")
    with build_lock(store.directory / "handoffs.lock"):
        if command == "journal":
            if request["operation"] in {"acquire", "reconnect"}:
                require(not pending_manager_operations(), "HANDOFF_PENDING", "保持中・結果不明の貸出操作が残っています")
            record = {"profileId": profile["profileId"], "profileHash": profile_hash(profile), "command": request["operation"], "payload": request["payload"],
                      "operationId": request["operationId"], "state": "UNKNOWN"}
            storage.write(path, encode(record))
        else:
            require_private(path)
            record = decode(path.read_bytes())
            require(record["profileHash"] == profile_hash(profile), "PROFILE_CHANGED", "プロファイルが変わっています")
            record["state"] = "COMPLETED"
            record["result"] = request["result"]
            staged = path.with_name(path.name + ".finished")
            storage.write(staged, encode(record))
            storage.move(staged, path, replace=True)
    return {"recorded": True}


def main():
    operation = ""
    try:
        body = sys.stdin.buffer.read(65537)
        if len(body) > 65536: raise ValueError()
        request = json.loads(body)
        operation = request["operationId"]
        result = execute(request)
        response = {"ok": True, "operationId": operation, "result": result}
    except Exception as error:
        response = {"ok": False, "operationId": operation,
                    "error": {"code": getattr(error, "code", "RESULT_UNKNOWN")}}
    sys.stdout.reconfigure(encoding="utf-8")
    print(json.dumps(response, ensure_ascii=False, allow_nan=False))


if __name__ == "__main__": main()
