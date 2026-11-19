import unittest
import random
import tempfile
import json
from unittest.mock import patch, Mock
from pathlib import Path
from simulator import FaultCycle, advance, credentials, read_reply, main, Connection


class SimulatorTest(unittest.TestCase):
    def test_hourly_readings_do_not_hide_short_fault_or_duplicate_consumption(self):
        clock = [0]
        published = []
        connection = Mock(connected=True)
        connection.device = {"id": "meter_0", "energyType": "water", "initialTotal": 100, "initialBalance": 50}
        connection.publish.side_effect = lambda values: published.append((clock[0], dict(values)))
        cycle = Mock()
        cycle.active.side_effect = lambda now: 'meter_0' if 60 <= now < 660 else None
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'config.json'
            config.write_text(json.dumps({'devices': [connection.device]}))
            with patch('sys.argv', ['simulator', '--config', str(config), '--state', str(Path(directory) / 'readings.json'), '--rounds', '2']), \
                 patch('simulator.Connection', return_value=connection), patch('simulator.FaultCycle', return_value=cycle), \
                 patch('simulator.signal.signal'), patch('simulator.time.monotonic', side_effect=lambda: clock[0]), \
                 patch('simulator.time.time', side_effect=lambda: clock[0]), \
                 patch('simulator.time.sleep', side_effect=lambda _: clock.__setitem__(0, clock[0] + 30)):
                main()
        readings = [(at, values) for at, values in published if 'total' in values]
        self.assertEqual([at for at, _ in readings], [0, 3600])
        self.assertEqual(readings[0][1]['total'], 100)
        self.assertEqual([(at, values) for at, values in published if 'total' not in values], [(60, {'fault': 1}), (660, {'fault': 0})])
        self.assertAlmostEqual(readings[1][1]['total'] - readings[0][1]['total'], readings[1][1]['increment'], places=6)

    def test_fault_only_publish_keeps_readable_meter_properties(self):
        connection = Connection.__new__(Connection)
        connection.properties = {'total': 123, 'increment': 0.4, 'fault': 0}
        connection.prefix = '/meter/properties'
        connection.device = {'id': 'meter_0'}
        connection.client = Mock()
        connection.client.publish.return_value.is_published.return_value = True
        connection.publish({'fault': 1})
        self.assertEqual(connection.properties, {'total': 123, 'increment': 0.4, 'fault': 1})
        payload = json.loads(connection.client.publish.call_args.args[1])
        self.assertEqual(payload['properties'], {'fault': 1})

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
            self.assertIsNone(cycle.active(289))
            second = cycle.active(290)
            self.assertIn(second, ["meter_0", "meter_1"])
            self.assertNotEqual(second, first)

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
            self.assertGreaterEqual(first_at, 3700)
            self.assertLessEqual(first_at, 7300)
            restarted = FaultCycle(config, devices, path, 200, random.Random(8))
            self.assertEqual(restarted.state["nextAt"], first_at)
            self.assertIsNone(restarted.active(first_at - 1))
            self.assertIn(restarted.active(first_at), ["meter_0", "meter_1"])
            recovered_at = restarted.state["until"]
            self.assertGreaterEqual(recovered_at - first_at, 540)
            self.assertLessEqual(recovered_at - first_at, 660)
            self.assertIsNone(restarted.active(recovered_at))
            next_at = restarted.state["nextAt"]
            self.assertGreaterEqual(next_at - first_at, 3600)
            self.assertLessEqual(next_at - first_at, 7200)
            restarted_again = FaultCycle(config, devices, path, recovered_at + 1)
            self.assertIsNone(restarted_again.active(next_at - 1))
            self.assertIsNotNone(restarted_again.active(next_at))

    def test_changing_frequency_reschedules_old_short_cycle_without_resetting_readings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "faults.json"
            devices = [{"id": "meter_0"}, {"id": "meter_1"}]
            old = {"faultSimulation": {"enabled": True, "normalSeconds": [180, 300],
                                       "faultSeconds": [120, 180]}}
            cycle = FaultCycle(old, devices, path, 100, random.Random(3))
            first_at = cycle.state["nextAt"]
            self.assertIsNotNone(cycle.active(first_at))
            updated = FaultCycle({"faultSimulation": {"enabled": True}}, devices,
                                 path, first_at + 30, random.Random(4))
            self.assertIsNone(updated.active(first_at + 30))
            self.assertGreaterEqual(updated.state["nextAt"] - first_at - 30, 3600)
            self.assertLessEqual(updated.state["nextAt"] - first_at - 30, 7200)

    def test_only_one_of_ten_devices_is_abnormal_during_ten_minute_fault(self):
        devices = [{"id": f"meter_{index}"} for index in range(10)]
        with tempfile.TemporaryDirectory() as directory:
            cycle = FaultCycle({"faultSimulation": {"enabled": True}}, devices,
                               Path(directory) / "faults.json", 100, random.Random(9))
            started = cycle.state["nextAt"]
            device = cycle.active(started)
            for now in range(int(started) + 1, int(cycle.state["until"]), 30):
                self.assertEqual(sum(item["id"] == cycle.active(now) for item in devices), 1)
                self.assertEqual(cycle.active(now), device)

    def test_invalid_first_delay_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            for delay in (-1, float("nan"), 86401, "30"):
                with self.subTest(delay=delay), self.assertRaises(ValueError):
                    FaultCycle({"faultSimulation": {"enabled": True, "firstDelaySeconds": delay}},
                               [{"id": "meter_0"}], Path(directory) / "faults.json", 0)


if __name__ == "__main__":
    unittest.main()
