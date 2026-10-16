"""private正本Storeと合成SSHによる接続・枠・writer隔離の試験。"""
import importlib.util
import io
import os
from pathlib import Path
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

CONTRACT = Path(os.environ["MANAGED_CONTRACT_DIRECTORY"])
sys.path.insert(0, str(CONTRACT / "scripts"))
from dev_server.connection_profiles import ConnectionStore
from dev_server.registry import RegistryError

spec = importlib.util.spec_from_file_location("manager_ssh_bridge", Path(__file__).parents[2] / "main/resources/managed/ssh-bridge.py")
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class FakeConnection:
    created = []
    def __init__(self, profile, instance, client, **options):
        self.profile = profile
        self.instance = instance
        self.calls = []
        self.closed = False
        self.unknown = False
        self.failure = False
        self.checked = {}
        self.created.append(self)

    def request(self, command, payload, *, operation_id, timeout):
        if self.unknown: raise RegistryError("CONNECTION_PENDING", "保留")
        self.calls.append((command, payload, operation_id))
        if self.failure:
            self.unknown = True
            raise RegistryError("SSH_UNAVAILABLE", "応答喪失")
        if command == "status": result = {"instances": [{"instanceId": self.instance}]}
        elif command == "lease-check": result = self.checked
        elif command == "close": self.closed = True; result = {"closed": True}
        elif command == "io": result = {"text": self.profile["host"] + "/" + self.instance}
        else: result = {"opened": True}
        return {"ok": True, "operationId": operation_id, "result": result}

    def _unknown(self): self.unknown = True


class SshBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="manager-197-", dir="/private/tmp" if sys.platform == "darwin" else None)
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "private"
        self.store = ConnectionStore(self.directory)
        self.profile = {"version": 1, "profileId": "remote-one", "displayName": "別PCの試験", "transport": "ssh", "sshUser": "test-user",
            "targetOs": "macos", "host": "remote-one", "managementRoot": "/別 PC/root", "python": "/別 PC/python", "cli": "/別 PC/scripts/dev-server.py",
            "instances": [{"instanceId": "persistent-01", "displayName": "枠1"}, {"instanceId": "persistent-02", "displayName": "枠2"}],
            "approval": {"operator": "test-owner", "reference": "合成試験のみ"}}
        self.store.save_profile(self.profile)
        FakeConnection.created = []
        patched = patch("dev_server.ssh_connection.SshConnection", FakeConnection)
        patched.start(); self.addCleanup(patched.stop)
        self.configuration = {"cli": str(CONTRACT / "scripts/dev-server.py"), "directory": str(self.directory), "profileId": self.profile["profileId"],
                              "expectedProfile": self.profile, "knownHosts": str(Path(self.temp.name) / "known_hosts")}
        self.manager = bridge.ManagerSshBridge(self.configuration)
        self.binding = {"instanceId": "persistent-01", "leaseId": "private-lease", "epoch": 1, "expectedGeneration": "generation-01",
                        "runId": "run-01", "nonce": "nonce-01", "sessionId": "session-01"}

    def call(self, command, payload=None):
        return self.manager.execute(command, payload or {}, "test-operation-" + str(sum(len(c.calls) for c in FakeConnection.created)))

    def opened(self):
        self.call("writer-open", dict(self.binding, owner="test-owner", pid=999999, cwd="wrong-host"))
        return self.manager.writers["persistent-01"]

    def test_remote_paths_stay_on_remote_and_foreign_candidates_are_not_used(self):
        self.assertEqual(2, len(self.call("list")["instances"]))
        self.assertEqual(["persistent-01", "persistent-02"], [c.instance for c in FakeConnection.created])
        with self.assertRaises(RegistryError): self.call("status", {"instanceId": "other-slot"})
        self.assertEqual("/別 PC/root", FakeConnection.created[0].profile["managementRoot"])

    def test_handoff_candidates_stay_in_local_private_store_without_remote_io(self):
        checked = {"instanceId": "persistent-01", "owner": "test-owner", "epoch": 1, "generation": "generation-01",
                   "generationBound": True, "runId": "run-01", "nonce": "nonce-01", "expiresAt": time.time() + 3600,
                   "state": "RUNNING", "pending": 0, "writerSessions": []}
        token = {key: self.binding[key] for key in ("instanceId", "leaseId", "epoch", "expectedGeneration")}
        handoff = self.store.offer(self.profile, token, checked, owner="test-owner", source_client="human-script", target_client="manager")
        offered = self.call("offers")["offers"]
        self.assertEqual([handoff], [row["handoffId"] for row in offered])
        self.assertFalse(FakeConnection.created)
        self.assertNotIn("private-lease", str(offered))

    def test_reconnect_uses_same_remote_lease_and_new_private_handoff(self):
        checked = {"instanceId": "persistent-01", "owner": "test-owner", "epoch": 1, "generation": "generation-01",
                   "generationBound": True, "runId": "run-01", "nonce": "nonce-01", "expiresAt": time.time() + 3600,
                   "state": "RUNNING", "pending": 0, "writerSessions": [{"status": "CLOSED"}]}
        token = {key: self.binding[key] for key in ("instanceId", "leaseId", "epoch", "expectedGeneration")}
        handoff = self.store.offer(self.profile, token, checked, owner="test-owner", source_client="human-script", target_client="manager")
        connection = self.manager.connection("persistent-01")
        connection.checked = checked
        self.call("claim", {"handoffId": handoff, "owner": "test-owner", "targetClient": "manager"})
        payload = {"handoffId": handoff, "owner": "test-owner"}
        self.manager.execute("journal", {"operation": "reconnect", "payload": payload}, "reconnect-probe")
        fresh = self.manager.execute("reoffer", payload, "reconnect-probe")
        self.assertNotEqual(handoff, fresh["handoffId"])
        self.assertEqual(["lease-check", "lease-check"], [call[0] for call in connection.calls])
        self.assertNotIn("private-lease", str(fresh))
        self.assertFalse(self.manager.writers)

    def test_server_native_writer_receives_no_remote_manager_pid(self):
        connection = self.opened()
        self.assertEqual(dict(self.binding, owner="test-owner"), connection.calls[0][1])
        self.assertEqual("open", connection.calls[0][0])

    def test_old_session_and_generation_cannot_save_or_close_current_writer(self):
        connection = self.opened()
        for field in ("sessionId", "expectedGeneration", "nonce", "leaseId"):
            old = dict(self.binding, **{field: "old"}, request={"action": "write"}, drained=True, disconnected=True)
            for command in ("writer-io", "writer-close"):
                with self.assertRaises(RegistryError): self.call(command, old)
        self.assertEqual(1, len(connection.calls))
        self.assertFalse(connection.closed)

    def test_write_result_unknown_does_not_create_another_writer_or_release(self):
        connection = self.opened(); connection.failure = True
        with self.assertRaises(RegistryError): self.call("writer-io", dict(self.binding, request={"action": "write"}))
        with self.assertRaises(RegistryError): self.opened()
        self.manager.abandon()
        self.assertTrue(connection.unknown)
        self.assertNotIn("close", [c[0] for c in connection.calls])
        self.assertNotIn("release", [c[0] for c in connection.calls])

    def test_normal_writer_close_keeps_control_connection_and_renew_responsibility(self):
        control = self.manager.connection("persistent-01")
        writer = self.opened()
        self.call("writer-close", dict(self.binding, drained=True, disconnected=True))
        self.assertTrue(writer.closed)
        self.assertFalse(control.closed)
        self.call("renew", dict(self.binding, ttlSeconds=3600))
        self.assertEqual("renew", control.calls[-1][0])
        self.call("finish-client")
        self.assertTrue(control.closed)
        self.assertNotIn("release", [c[0] for c in control.calls])

    def test_two_hosts_and_slots_have_separate_response_connections(self):
        self.call("list")
        other = dict(self.profile, profileId="remote-two", host="remote-two")
        self.store.save_profile(other)
        second = bridge.ManagerSshBridge(dict(self.configuration, profileId="remote-two", expectedProfile=other))
        self.opened()
        second.execute("writer-open", dict(self.binding, owner="test-owner"), "other-open")
        first = self.call("writer-io", dict(self.binding, request={"action": "read"}))
        result = second.execute("writer-io", dict(self.binding, request={"action": "read"}), "other-read")
        self.assertNotEqual(first, result)
        self.assertIsNot(self.manager.writers["persistent-01"], second.writers["persistent-01"])

    def test_profile_edit_is_rejected_before_new_connection(self):
        self.store.save_profile(dict(self.profile, host="remote-two"), replace=True)
        with self.assertRaises(RegistryError): self.call("list")
        self.assertEqual([], FakeConnection.created)

    def test_claim_uses_same_ssh_lease_check_once_and_never_acquires(self):
        token = {key: self.binding[key] for key in ("instanceId", "leaseId", "epoch", "expectedGeneration")}
        checked = dict(instanceId="persistent-01", owner="test-owner", epoch=1, generation="generation-01", generationBound=True,
                       runId="run-01", nonce="nonce-01", expiresAt=time.time()+3600, state="RUNNING", pending=0, writerSessions=[])
        offer = self.store.offer(self.profile, token, checked, owner="test-owner", source_client="human", target_client="manager")
        control = self.manager.connection("persistent-01"); control.checked = checked
        payload = dict(handoffId=offer, owner="test-owner", targetClient="manager")
        result = self.call("claim", payload)
        self.assertEqual("CLAIMED", result["handoff"]["state"])
        with self.assertRaises(RegistryError): self.call("claim", payload)
        self.assertEqual(["lease-check"], [c[0] for c in control.calls])

    def test_finish_with_writer_pending_is_rejected(self):
        writer = self.opened()
        with self.assertRaises(RegistryError): self.call("finish-client")
        self.assertFalse(writer.closed)

    def test_frame_rejects_oversize_and_truncated_response(self):
        for body in ((65537).to_bytes(4,"big"), b"\x00\x00", (5).to_bytes(4,"big")+b"{}"):
            with self.assertRaises((ValueError, EOFError, RegistryError)): bridge.read_frame(io.BytesIO(body))


if __name__ == "__main__": unittest.main()
