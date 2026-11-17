import unittest
import random
import tempfile
from pathlib import Path
from simulator import FaultCycle, advance, credentials, read_reply


class SimulatorTest(unittest.TestCase):
    def setUp(self):
        self.device = {"id": "local_electricity_0", "energyType": "electricity",
                       "initialTotal": 100, "initialBalance": 50}

    def test_counters_continue_from_saved_state_and_balance_never_goes_negative(self):
        first = advance({}, self.device, 30, 100)
        second = advance(first, self.device, 30, 130)
        self.assertGreater(first["total"], 100)
        self.assertGreater(second["total"], first["total"])
        self.assertAlmostEqual(second["total"] - first["total"], second["increment"], places=6)
        self.assertLess(second["balance"], first["balance"])
        self.assertEqual(advance({"balance": 0}, self.device, 30, 100)["balance"], 0)

    def test_water_does_not_claim_electrical_power(self):
        self.device["energyType"] = "water"
        self.assertNotIn("power", advance({}, self.device, 30, 100))

    def test_read_reply_preserves_request_id_and_only_returns_requested_properties(self):
        reply = read_reply({"messageId": "read-123", "properties": ["total"]},
                           {"total": 10, "balance": 20}, 1000)
        self.assertEqual(reply, {"messageId": "read-123", "timestamp": 1000,
                                 "success": True, "properties": {"total": 10}})

    def test_official_authentication_uses_signed_timestamp(self):
        username, password = credentials("device", "secret", 1000)
        self.assertEqual(username, "device|1000")
        self.assertEqual(password, "fe8c6009dd83cad33ceaa142cb5b864e")

    def test_fault_cycle_recovers_before_a_different_device_fails_and_survives_restart(self):
        devices = [{"id": "meter_0"}, {"id": "meter_1"}]
        config = {"faultSimulation": {"enabled": True, "firstDelaySeconds": 10,
                                     "faultSeconds": [120, 120], "normalSeconds": [180, 180]}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "faults.json"
            cycle = FaultCycle(config, devices, path, 100, random.Random(1))
            self.assertIsNone(cycle.active(109))
            first = cycle.active(110)
            self.assertIn(first, ["meter_0", "meter_1"])
            restored = FaultCycle(config, devices, path, 150, random.Random(1))
            self.assertEqual(restored.active(150), first)
            self.assertIsNone(cycle.active(230))
            self.assertIsNone(cycle.active(409))
            self.assertNotEqual(cycle.active(410), first)

    def test_fault_simulation_is_disabled_without_explicit_configuration(self):
        with tempfile.TemporaryDirectory() as directory:
            cycle = FaultCycle({}, [{"id": "meter_0"}], Path(directory) / "faults.json", 0)
            self.assertIsNone(cycle.active(100000))

    def test_default_faults_are_sparse_and_cooldown_survives_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "faults.json"
            config = {"faultSimulation": {"enabled": True}}
            devices = [{"id": "meter_0"}, {"id": "meter_1"}]
            cycle = FaultCycle(config, devices, path, 100, random.Random(7))
            first_at = cycle.state["nextAt"]
            self.assertGreaterEqual(first_at, 700)
            self.assertLessEqual(first_at, 1300)
            restarted = FaultCycle(config, devices, path, 200, random.Random(8))
            self.assertEqual(restarted.state["nextAt"], first_at)
            self.assertIsNone(restarted.active(first_at - 1))
            self.assertIn(restarted.active(first_at), ["meter_0", "meter_1"])
            recovered_at = restarted.state["until"]
            self.assertGreaterEqual(recovered_at - first_at, 30)
            self.assertLessEqual(recovered_at - first_at, 90)
            self.assertIsNone(restarted.active(recovered_at))
            next_at = restarted.state["nextAt"]
            self.assertGreaterEqual(next_at - recovered_at, 600)
            self.assertLessEqual(next_at - recovered_at, 1200)
            restarted_again = FaultCycle(config, devices, path, recovered_at + 1)
            self.assertIsNone(restarted_again.active(next_at - 1))
            self.assertIsNotNone(restarted_again.active(next_at))

    def test_invalid_first_delay_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for delay in (-1, float("nan"), 86401, "30"):
                with self.subTest(delay=delay), self.assertRaises(ValueError):
                    FaultCycle({"faultSimulation": {"enabled": True, "firstDelaySeconds": delay}},
                               [{"id": "meter_0"}], Path(directory) / "faults.json", 0)


if __name__ == "__main__":
    unittest.main()
