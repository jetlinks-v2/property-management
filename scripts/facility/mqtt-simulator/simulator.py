"""Report local water/electricity/gas readings through JetLinks' MQTT protocol."""
import argparse
import hashlib
import json
import math
import random
import signal
import sys
import time
import uuid
from pathlib import Path


def credentials(secure_id, secure_key, timestamp):
    username = f"{secure_id}|{timestamp}"
    # The existing official protocol requires this digest; it is not a stored password.
    password = hashlib.md5(f"{username}|{secure_key}".encode()).hexdigest()
    return username, password


def advance(previous, device, elapsed, now):
    kind = device["energyType"]
    hourly = {"water": 0.12, "gas": 0.18, "electricity": 1.8}[kind]
    index = int(device["id"].rsplit("_", 1)[-1])
    rate = hourly * (1 + index * 0.15) * (1 + 0.12 * math.sin(now / 180))
    increment = round(rate * elapsed / 3600, 6)
    total = round(previous.get("total", device["initialTotal"]) + increment, 6)
    balance = round(max(0, previous.get("balance", device["initialBalance"]) -
                          increment * {"water": 3.2, "gas": 2.1, "electricity": 0.65}[kind]), 6)
    result = {"total": total, "increment": increment, "balance": balance, "fault": 0}
    if kind == "electricity":
        result["power"] = round(rate, 3)
    return result


def report(properties, now):
    return {"messageId": uuid.uuid4().hex, "timestamp": now, "properties": properties}


def read_reply(request, properties, now):
    wanted = request.get("properties", list(properties))
    return {"messageId": request["messageId"], "timestamp": now, "success": True,
            "properties": {key: properties[key] for key in wanted if key in properties}}


def checkpoint(path, states):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(states, indent=2), encoding="utf-8")
    temporary.replace(path)


def log(event, **fields):
    print(json.dumps({"event": event, **fields}), flush=True)


class FaultCycle:
    """Only configured demo devices may fail; at most one fault stays active at a time."""
    def __init__(self, config, devices, path, now, rng=None):
        self.settings = config.get("faultSimulation", {})
        self.enabled = self.settings.get("enabled", False)
        self.devices = [device["id"] for device in devices]
        self.path, self.rng = path, rng or random.Random()
        self.last_device = None
        self.normal_seconds = self.settings.get("normalSeconds", [3600, 7200])
        self.fault_seconds = self.settings.get("faultSeconds", [540, 660])
        for key, bounds in (("normalSeconds", self.normal_seconds), ("faultSeconds", self.fault_seconds)):
            if len(bounds) != 2 or not 30 <= bounds[0] <= bounds[1] <= 86400:
                raise ValueError(f"Invalid fault simulation duration: {key}")
        self.schedule = {"normalSeconds": self.normal_seconds, "faultSeconds": self.fault_seconds}
        self.state = json.loads(path.read_text()) if path.exists() else None
        # Old persisted short cycles must not override a newly configured low frequency.
        if self.state is None or self.state.get("schedule") != self.schedule:
            delay = self.settings.get("firstDelaySeconds") if self.state is None else None
            if delay is None:
                delay = self.rng.uniform(*self.normal_seconds)
            if not isinstance(delay, (int, float)) or not math.isfinite(delay) or not 0 <= delay <= 86400:
                raise ValueError("Invalid fault simulation duration: firstDelaySeconds")
            self.state = {"device": None, "until": 0, "nextAt": now + delay, "schedule": self.schedule}
            if self.enabled:
                checkpoint(path, self.state)

    def active(self, now):
        if not self.enabled:
            return None
        if self.state["device"] and (self.state["device"] not in self.devices or now >= self.state["until"]):
            log("fault_recovered", device=self.state["device"])
            next_at = self.state["nextAt"]
            if next_at <= now:
                next_at = now + self.rng.uniform(*self.normal_seconds)
            self.state = {"device": None, "until": 0,
                          "nextAt": next_at, "schedule": self.schedule}
            checkpoint(self.path, self.state)
        if not self.state["device"] and now >= self.state["nextAt"]:
            choices = [device for device in self.devices if device != self.last_device] or self.devices
            device = self.rng.choice(choices)
            self.last_device = device
            self.state = {"device": device, "until": now + self.rng.uniform(
                *self.fault_seconds), "nextAt": now + self.rng.uniform(*self.normal_seconds),
                "schedule": self.schedule}
            checkpoint(self.path, self.state)
            log("fault_started", device=device, recoverAt=self.state["until"])
        return self.state["device"]


class Connection:
    def __init__(self, device, config, previous):
        import paho.mqtt.client as mqtt
        self.device, self.config = device, config
        self.properties = previous
        self.connected = False
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2,
                                  client_id=device["id"], reconnect_on_failure=False)
        self.client.on_connect = self.on_connect
        self.client.on_disconnect = self.on_disconnect
        self.client.on_message = self.on_message
        self.prefix = f"/{device['productId']}/{device['id']}/properties"

    def on_connect(self, client, userdata, flags, reason, properties):
        self.connected = not reason.is_failure
        if self.connected:
            client.subscribe(self.prefix + "/read", qos=1)
        log("connected" if self.connected else "connection_rejected",
            device=self.device["id"], reason=str(reason))

    def on_disconnect(self, client, userdata, flags, reason, properties):
        self.connected = False
        log("disconnected", device=self.device["id"], reason=str(reason))

    def on_message(self, client, userdata, message):
        if message.topic != self.prefix + "/read":
            return
        try:
            payload = read_reply(json.loads(message.payload), self.properties, int(time.time() * 1000))
            client.publish(self.prefix + "/read/reply", json.dumps(payload), qos=1)
            log("read_replied", device=self.device["id"], messageId=payload["messageId"])
        except (KeyError, ValueError, TypeError):
            log("invalid_read_request", device=self.device["id"])

    def connect(self):
        # Regenerate the signed timestamp on every reconnect (the protocol allows five minutes).
        self.client.username_pw_set(*credentials(self.device["secureId"], self.device["secureKey"],
                                                int(time.time() * 1000)))
        self.client.connect(self.config["host"], self.config["port"], keepalive=60)
        deadline = time.monotonic() + 10
        while not self.connected and time.monotonic() < deadline:
            self.client.loop(timeout=0.1)
        if not self.connected:
            raise RuntimeError(f"MQTT connection failed: {self.device['id']}")

    def publish(self, properties):
        self.properties.update(properties)
        payload = report(properties, int(time.time() * 1000))
        sent = self.client.publish(self.prefix + "/report", json.dumps(payload), qos=1, retain=False)
        deadline = time.monotonic() + 10
        while not sent.is_published() and time.monotonic() < deadline:
            self.client.loop(timeout=0.1)
        if not sent.is_published():
            raise RuntimeError(f"MQTT publish was not acknowledged: {self.device['id']}")
        log("reported", device=self.device["id"], messageId=payload["messageId"], properties=properties)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", default="/config/config.json")
    parser.add_argument("--state", default="/state/readings.json")
    parser.add_argument("--interval", type=float, default=3600)
    parser.add_argument("--rounds", type=int, default=0, help="0 means continue until stopped")
    parser.add_argument("--only", help="Run one configured device for verification")
    args = parser.parse_args()
    if not math.isfinite(args.interval) or args.interval < 1 or args.rounds < 0:
        parser.error("interval must be >= 1 and rounds must be >= 0")
    config = json.loads(Path(args.config).read_text(encoding="utf-8-sig"))
    devices = [d for d in config["devices"] if not args.only or d["id"] == args.only]
    if not devices or len({d['id'] for d in devices}) != len(devices):
        parser.error("device list must be nonempty with unique IDs")
    path = Path(args.state)
    path.parent.mkdir(parents=True, exist_ok=True)
    states = json.loads(path.read_text()) if path.exists() else {}
    faults = FaultCycle(config, devices, path.with_name("faults.json"), time.time())
    running = True

    def stop(signum, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    connections = [Connection(d, config, states.get(d["id"], {})) for d in devices]
    try:
        for connection in connections:
            connection.connect()
        last = time.monotonic() - args.interval
        last_check = time.monotonic() - 30
        rounds = 0
        while running and (not args.rounds or rounds < args.rounds):
            now = time.monotonic()
            full_report = now - last >= args.interval
            if full_report or now - last_check >= 30:
                elapsed = min(now - last, args.interval * 2) if rounds else 0
                faulty_device = faults.active(time.time())
                for connection in connections:
                    if not running:
                        break
                    if not connection.connected:
                        # Bound retries; fail the process visibly instead of pretending data is flowing.
                        for attempt in range(3):
                            try:
                                connection.connect()
                                break
                            except (OSError, RuntimeError):
                                log("reconnect_failed", device=connection.device["id"], attempt=attempt + 1)
                                if attempt == 2:
                                    raise
                                time.sleep(2)
                    key = connection.device["id"]
                    fault = int(key == faulty_device)
                    if full_report:
                        values = advance(states.get(key, {}), connection.device, elapsed, time.time())
                        values["fault"] = fault
                    elif states.get(key, {}).get("fault", 0) != fault:
                        # Fault transitions must not resend the last hourly consumption increment.
                        values = {"fault": fault}
                    else:
                        continue
                    connection.publish(values)
                    states.setdefault(key, {}).update(values)
                    checkpoint(path, states)
                last_check = now
                if full_report:
                    last = now
                    rounds += 1
            for connection in connections:
                connection.client.loop(timeout=0.01)
            time.sleep(0.05)
    finally:
        for connection in connections:
            connection.client.disconnect()
            connection.client.loop(timeout=0.1)
        log("stopped")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Never log connection configuration or credentials.
        log("failed", error=type(error).__name__, message=str(error))
        sys.exit(1)
