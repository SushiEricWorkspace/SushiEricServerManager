"""使い捨てprivate記録と合成lease-checkによるManager bridgeの契約試験。"""
import importlib.util
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

CONTRACT = Path(os.environ["MANAGED_CONTRACT_DIRECTORY"])
sys.path.insert(0, str(CONTRACT / "scripts"))
from dev_server.connection_profiles import ConnectionStore
from dev_server.local_ipc import require_private
from dev_server.registry import RegistryError

BRIDGE = Path(__file__).parents[2] / "main/resources/managed/connection-bridge.py"
spec = importlib.util.spec_from_file_location("manager_bridge", BRIDGE)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class BridgeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="manager-196-", dir="/private/tmp" if sys.platform == "darwin" else None)
        self.addCleanup(self.temp.cleanup)
        root = Path(self.temp.name)
        self.directory = root / "private"
        self.store = ConnectionStore(self.directory)
        self.profile = {"version": 1, "profileId": "test-local", "displayName": "試験 枠", "transport": "local",
                        "targetOs": "windows" if sys.platform == "win32" else "macos", "host": socket.gethostname(),
                        "managementRoot": str(root / "fake-root"), "python": str(Path(sys.executable)),
                        "cli": str(CONTRACT / "scripts/dev-server.py"),
                        "instances": [{"instanceId": "persistent-01", "displayName": "試験1"}],
                        "approval": {"operator": "test-owner", "reference": "合成試験だけ"}}
        self.store.save_profile(self.profile)
        self.token = {"instanceId": "persistent-01", "leaseId": "secret-lease", "epoch": 2, "expectedGeneration": "generation-01"}
        self.checked = {"instanceId": "persistent-01", "owner": "test-owner", "epoch": 2, "generation": "generation-01",
                        "generationBound": True, "runId": "run-01", "nonce": "nonce-01", "expiresAt": time.time() + 3600,
                        "state": "RUNNING", "pending": 0, "writerSessions": []}

    def request(self, command, operation_id="probe", **options):
        return bridge.execute(dict(command=command, operationId=operation_id, cli=self.profile["cli"],
                                   directory=str(self.directory), profileId=self.profile["profileId"], expectedProfile=self.profile, **options))

    def offer(self):
        return self.store.offer(self.profile, self.token, self.checked, owner="test-owner", source_client="human-script", target_client="manager")

    def test_profile_is_non_secret_and_wrong_host_or_ssh_is_rejected(self):
        self.assertEqual(self.profile, self.request("profile")["profile"])
        self.assertNotIn("secret-lease", json.dumps(self.profile))
        with patch("socket.gethostname", return_value="other-host"):
            with self.assertRaises(RegistryError): self.request("profile")
        self.profile["transport"] = "ssh"
        self.profile["sshUser"] = "test-user"
        self.store.save_profile(self.profile, replace=True)
        with self.assertRaises(RegistryError): self.request("profile")

    def test_offers_return_only_live_same_profile_manager_candidates_without_secrets(self):
        from dev_server import storage
        ids = [self.offer() for _ in range(2)]
        for change in ({"targetClient": "other-client"}, {"state": "CLAIMED"}, {"state": "UNKNOWN"}, {"expiresAt": 1}, {"profileHash": "a" * 64}):
            handoff = self.offer()
            path = self.directory / "handoffs" / (handoff + ".json")
            record = json.loads(path.read_bytes())
            record.update(change)
            self.store._write(path, record, replace=True)
        with patch("dev_server.connection_profiles.local_request") as callback:
            offered = self.request("offers")["offers"]
            callback.assert_not_called()
        self.assertEqual(set(ids), {row["handoffId"] for row in offered})
        self.assertNotIn("secret-lease", json.dumps(offered))
        self.assertTrue(all("token" not in row and "leaseId" not in row and "sessionId" not in row for row in offered))
        self.assertTrue(all(row["state"] == "OFFERED" for row in (json.loads((self.directory / "handoffs" / (value + ".json")).read_bytes()) for value in ids)))
        # 不完全な記録を候補として無視して正常扱いしません。
        storage.write(self.directory / "handoffs/interrupted.json.writing", b"partial")
        with self.assertRaises(RegistryError): self.request("offers")

    def test_normal_reconnect_creates_new_handoff_and_session_without_reusing_or_renewing(self):
        handoff = self.offer()
        with patch("dev_server.connection_profiles.local_request", return_value=self.checked):
            self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
        old = self.store._read(self.directory / "handoffs" / (handoff + ".json"))
        old["expiresAt"] = 1
        self.store._write(self.directory / "handoffs" / (handoff + ".json"), old, replace=True)
        payload = {"handoffId": handoff, "owner": "test-owner"}
        self.request("journal", "reconnect-probe", operation="reconnect", payload=payload)
        checked = dict(self.checked, writerSessions=[{"status": "CLOSED"}])
        with patch("dev_server.connection_profiles.local_request", return_value=checked) as callback:
            result = self.request("reoffer", "reconnect-probe", **payload)
            with self.assertRaises(RegistryError): self.request("reoffer", "reconnect-probe", **payload)
            self.assertEqual(1, callback.call_count)
            self.assertEqual("lease-check", callback.call_args.args[1])
        fresh = self.store._read(self.directory / "handoffs" / (result["handoffId"] + ".json"))
        self.assertNotEqual(handoff, fresh["handoffId"])
        self.assertNotEqual(old["sessionId"], fresh["sessionId"])
        self.assertEqual(old["token"], fresh["token"])
        self.assertEqual(old["sourceClient"], fresh["sourceClient"])
        self.assertEqual("OFFERED", fresh["state"])
        self.assertNotIn("secret-lease", json.dumps(result))
        self.assertEqual(old, self.store._read(self.directory / "handoffs" / (handoff + ".json")))
        self.request("finish", "reconnect-probe", result=result)
        with patch("dev_server.connection_profiles.local_request", return_value=checked):
            self.request("claim", handoffId=fresh["handoffId"], owner="test-owner", targetClient="manager")
        self.assertFalse(self.request("profile")["pendingManagerOperation"])

    def test_reconnect_unknown_writer_is_preserved_without_new_handoff_or_retry(self):
        handoff = self.offer()
        with patch("dev_server.connection_profiles.local_request", return_value=self.checked):
            self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
        payload = {"handoffId": handoff, "owner": "test-owner"}
        self.request("journal", "reconnect-probe", operation="reconnect", payload=payload)
        checked = dict(self.checked, writerSessions=[{"status": "UNKNOWN"}])
        with patch("dev_server.connection_profiles.local_request", return_value=checked) as callback:
            with self.assertRaises(RegistryError): self.request("reoffer", "reconnect-probe", **payload)
            with self.assertRaises(RegistryError): self.request("reoffer", "reconnect-probe", **payload)
            self.assertEqual(1, callback.call_count)
        self.assertEqual(1, len(list((self.directory / "handoffs").glob("*.json"))))
        self.assertTrue(self.request("profile")["pendingManagerOperation"])
        with self.assertRaises(RegistryError): self.request("journal", "another-reconnect", operation="reconnect", payload=payload)

    def test_once_only_claim_preserves_shared_session_and_does_not_acquire_or_renew(self):
        handoff = self.offer()
        with patch("dev_server.connection_profiles.local_request", return_value=self.checked) as callback:
            result = self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
            self.assertEqual("CLAIMED", result["handoff"]["state"])
            self.assertEqual(self.token, result["handoff"]["token"])
            self.assertEqual("lease-check", callback.call_args.args[1])
            with self.assertRaises(RegistryError): self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
            self.assertEqual(1, callback.call_count)

    def test_claim_deadline_does_not_override_renewed_live_lease_expiry(self):
        handoff = self.offer()
        renewed = dict(self.checked, expiresAt=self.checked["expiresAt"] + 3600)
        with patch("dev_server.connection_profiles.local_request", return_value=renewed):
            result = self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
        self.assertEqual(self.checked["expiresAt"], result["handoff"]["expiresAt"])
        self.assertEqual(renewed["expiresAt"], result["checked"]["expiresAt"])

    def test_other_owner_and_client_are_rejected_without_touching_supervisor(self):
        handoff = self.offer()
        with patch("dev_server.connection_profiles.local_request") as callback:
            for owner, client in (("other-owner", "manager"), ("test-owner", "other-client")):
                with self.assertRaises(RegistryError): self.request("claim", handoffId=handoff, owner=owner, targetClient=client)
            callback.assert_not_called()

    def test_old_epoch_generation_run_and_unknown_response_are_retained(self):
        for change in ({"epoch": 3}, {"generation": "old"}, {"nonce": "old"}, {"runId": "old"}, {"expiresAt": 1}):
            handoff = self.offer()
            with patch("dev_server.connection_profiles.local_request", return_value=dict(self.checked, **change)):
                with self.assertRaises(RegistryError): self.request("claim", handoffId=handoff, owner="test-owner", targetClient="manager")
            record = json.loads((self.directory / "handoffs" / (handoff + ".json")).read_bytes())
            self.assertEqual("UNKNOWN", record["state"])

    def test_mutation_is_durable_before_send_and_unknown_operation_blocks_restart(self):
        self.request("journal", "acquire-probe", operation="acquire", payload={"instanceId": "persistent-01", "owner": "test-owner", "ttlSeconds": 3600})
        path = self.directory / "manager-operations/acquire-probe.json"
        require_private(path)
        self.assertEqual("UNKNOWN", json.loads(path.read_bytes())["state"])
        self.assertTrue(self.request("profile")["pendingManagerOperation"])
        with self.assertRaises(RegistryError): self.request("journal", "acquire-probe", operation="acquire", payload={})

    def test_completed_acquire_survives_app_exit_until_explicit_release(self):
        self.request("journal", "acquire-probe", operation="acquire", payload={})
        self.request("finish", "acquire-probe", result=dict(self.token, generation="generation-01"))
        self.assertTrue(self.request("profile")["pendingManagerOperation"])
        self.request("journal", "release-probe", operation="release", payload=self.token)
        self.request("finish", "release-probe", result={"released": True})
        self.assertFalse(self.request("profile")["pendingManagerOperation"])
        self.assertNotIn("secret-lease", (self.directory / "profiles/test-local.json").read_text(encoding="utf-8"))

    def test_partial_publication_and_hardlinked_operation_are_preserved_and_rejected(self):
        from dev_server import storage
        directory = self.directory / "manager-operations"
        storage.mkdir(directory)
        partial = directory / "interrupted.json.writing"
        storage.write(partial, b"partial")
        self.assertTrue(self.request("profile")["pendingManagerOperation"])
        with self.assertRaises(RegistryError): self.request("journal", "new-acquire", operation="acquire", payload={})
        self.assertEqual(b"partial", partial.read_bytes())
        linked = directory / "linked.json"
        os.link(partial, linked)
        with self.assertRaises(RegistryError): self.request("profile")

    def test_edited_profile_is_not_used_by_inflight_old_client(self):
        expected = dict(self.profile)
        self.profile["managementRoot"] += "-changed"
        self.store.save_profile(self.profile, replace=True)
        with self.assertRaises(RegistryError):
            bridge.execute(dict(command="journal", operationId="stale-profile", operation="acquire", payload={},
                                cli=self.profile["cli"], directory=str(self.directory), profileId=self.profile["profileId"], expectedProfile=expected))
        self.assertFalse((self.directory / "manager-operations").exists())


if __name__ == "__main__": unittest.main(verbosity=2)
