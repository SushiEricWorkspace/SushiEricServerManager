"""正本SshConnectionをManagerの貸出・writer・監視へ結ぶ。stdoutはframe専用。"""
import importlib.util
from pathlib import Path
import sys
import uuid

if __name__ == "__main__": sys.path.insert(0, str(Path(sys.argv[1]).parent))
from dev_server.ssh_protocol import read_frame, write_frame
from dev_server.registry import RegistryError


class ManagerSshBridge:
    def __init__(self, configuration):
        self.configuration = configuration
        sys.path.insert(0, str(Path(configuration["cli"]).parent))
        from dev_server.registry import require
        from dev_server.connection_profiles import ConnectionStore
        require(sys.version_info[:2] == (3, 12), "UNAVAILABLE", "Python 3.12が必要です")
        self.store = ConnectionStore(Path(configuration["directory"]))
        self.profile = self.store.profile(configuration["profileId"])
        require(self.profile == configuration["expectedProfile"] and self.profile["transport"] == "ssh",
                "PROFILE_CHANGED", "固定SSHプロファイルが必要です")
        self.controls = {}
        self.writers = {}
        self.bindings = {}
        spec = importlib.util.spec_from_file_location("manager_profile_bridge", Path(__file__).with_name("connection-bridge.py"))
        self.private_bridge = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.private_bridge)

    def connection(self, instance, *, writer=False):
        from dev_server.registry import require
        from dev_server.ssh_connection import SshConnection
        require(self.store.profile(self.profile["profileId"]) == self.profile, "PROFILE_CHANGED", "プロファイルが変わっています")
        require(instance in {row["instanceId"] for row in self.profile["instances"]}, "TARGET_MISMATCH", "候補の同じ枠が必要です")
        connections = self.writers if writer else self.controls
        if instance not in connections:
            connections[instance] = SshConnection(self.profile, instance, "manager-" + uuid.uuid4().hex,
                known_hosts=Path(self.configuration["knownHosts"]), journal_parent=self.store.directory, timeout=15)
        return connections[instance]

    @staticmethod
    def result(connection, command, payload, operation):
        from dev_server.registry import RegistryError
        response = connection.request(command, payload, operation_id=operation, timeout=40)
        if not response["ok"]:
            error = response["error"]
            raise RegistryError(error["code"], "SSH操作を確認してください", rejected_before_effect=error.get("rejectedBeforeEffect") is True)
        return response["result"]

    def execute(self, command, payload, operation):
        from dev_server.registry import require
        from dev_server.connection_profiles import TOKEN_FIELDS
        require(self.store.profile(self.profile["profileId"]) == self.profile, "PROFILE_CHANGED", "プロファイルが変わっています")
        if command in {"profile", "journal", "finish", "claim", "offers", "reoffer"}:
            request = dict(self.configuration, **payload, command=command, operationId=operation)
            return self.private_bridge.execute(request, lease_check=lambda token:
                self.result(self.connection(token["instanceId"]), "lease-check", token, operation))
        if command == "list":
            rows = []
            for candidate in self.profile["instances"]:
                instance = candidate["instanceId"]
                response = self.result(self.connection(instance), "status", {"instanceId": instance}, uuid.uuid4().hex)
                require(len(response["instances"]) == 1 and response["instances"][0]["instanceId"] == instance,
                        "TARGET_MISMATCH", "枠状態の応答が一致しません")
                rows.extend(response["instances"])
            return {"instances": rows}
        if command == "finish-client":
            require(not self.bindings, "WRITER_PENDING", "writerを先に正常終了してください")
            for connection in self.controls.values(): self.result(connection, "close", {}, uuid.uuid4().hex)
            return {"closed": True}
        instance = payload["instanceId"]
        if command == "writer-open":
            require(instance not in self.bindings, "WRITER_PENDING", "既存writerを保全してください")
            names = TOKEN_FIELDS | {"sessionId", "runId", "nonce"}
            binding = {name: payload[name] for name in names}
            self.bindings[instance] = binding
            return self.result(self.connection(instance, writer=True), "open", dict(binding, owner=payload["owner"]), operation)
        if command in {"writer-io", "writer-close"} or command.startswith("monitor-"):
            require(instance in self.bindings, "NOT_CONNECTED", "同じwriter接続が必要です")
            if command in {"writer-io", "writer-close"}:
                require(all(payload.get(k) == v for k, v in self.bindings[instance].items()), "STALE_RUN", "固定writerが一致しません")
            connection = self.writers[instance]
            if command == "writer-io": return self.result(connection, "io", {"request": payload["request"]}, operation)
            if command == "writer-close":
                require(payload.get("drained") is True and payload.get("disconnected") is True,
                        "WRITER_PENDING", "queueと監視Closeを確認してください")
                result = self.result(connection, "close", {}, operation)
                require(result.get("closed") is True, "WRITER_PENDING", "終了が未確認です")
                del self.bindings[instance]
                del self.writers[instance]
                return result
            return self.result(connection, command, {key: value for key, value in payload.items() if key != "instanceId"}, operation)
        from dev_server.ssh_protocol import CLI_COMMANDS
        require(command in CLI_COMMANDS, "INVALID_INPUT", "仲介の許可対象外です")
        return self.result(self.connection(instance), command, payload, operation)

    def abandon(self):
        for connection in [*self.controls.values(), *self.writers.values()]:
            if not connection.closed: connection._unknown()


def main():
    bridge = None
    finished = False
    try:
        while True:
            request = read_frame(sys.stdin.buffer)
            operation = request["operationId"]
            try:
                command = request["command"]
                if bridge is None:
                    if command != "configure": raise ValueError()
                    bridge = ManagerSshBridge(request["payload"])
                    result = {"ready": True}
                else:
                    result = bridge.execute(command, request["payload"], operation)
                response = {"ok": True, "operationId": operation, "result": result}
            except Exception as error:
                response = {"ok": False, "operationId": operation, "error": {"code": getattr(error, "code", "RESULT_UNKNOWN")}}
                if getattr(error, "rejected_before_effect", False): response["error"]["rejectedBeforeEffect"] = True
            write_frame(sys.stdout.buffer, response)
            if command == "finish-client" and response["ok"]:
                finished = True
                break
            if not response["ok"] and not response["error"].get("rejectedBeforeEffect"): break
    except (EOFError, OSError, ValueError, KeyError, RegistryError):
        pass
    finally:
        if bridge is not None and not finished: bridge.abandon()


if __name__ == "__main__": main()
