"""WebSocket signaling client for the Blackmagic Camera app's remote camera control.

Wire format (JSON text frames): {"type": <message type>, "data": {...}}
  controller -> hello_request  {info: ParticipantInfo, nonce: hex(32 random bytes)}
  camera     -> hello_response {info, controller_info, status: ready|busy, nonce: hex(client_nonce || server_nonce)}
  controller -> conn_request   {password: hex(sha256(nonce64 || sha256(pw || SALT1) || SALT2))}
  camera     -> conn_response  {status: authorized|auth_error|busy}
  either     -> sdp_offer / sdp_answer / ice_candidate {value: str}, bye_message {reason}
"""
import asyncio
import hashlib
import json
import logging
import os
import uuid as uuidlib

import websockets

log = logging.getLogger("cineremote.signaling")

PROTO_MAJOR = 5
PROTO_MINOR = 0
_SALT1 = bytes.fromhex("0e7b97a67bd5d0ed")
_SALT2 = bytes.fromhex("55813b6755c037b1")


class AuthError(Exception):
    pass


class BusyError(Exception):
    pass


def password_hash(password: str, nonce: bytes) -> str:
    if len(nonce) != 64:
        raise ValueError("nonce must be 64 bytes")
    inner = hashlib.sha256(password.encode("utf-8") + _SALT1).digest()
    return hashlib.sha256(nonce + inner + _SALT2).hexdigest()


def participant_info(device_uuid, name, model="Windows PC", slate_name=None):
    return {
        "uuid": str(device_uuid),
        "model_name": model,
        "device_name": name,
        "slate_name": slate_name or name,
        "proto_major": PROTO_MAJOR,
        "proto_minor": PROTO_MINOR,
    }


class SignalingClient:
    """One controller->camera signaling session. Call connect(), then authenticate()."""

    def __init__(self, host, port, password, info):
        self.host, self.port, self.password, self.info = host, port, password, info
        self.ws = None
        self.remote_info = None
        self.queue = asyncio.Queue()  # post-auth messages: (type, data)
        self.authorized = False
        self._reader = None

    async def connect(self, timeout=10):
        url = f"ws://{self.host}:{self.port}"
        log.info("connecting %s", url)
        self.ws = await asyncio.wait_for(
            websockets.connect(url, max_size=None, ping_interval=None, open_timeout=timeout), timeout)

    async def send(self, mtype, data):
        txt = json.dumps({"type": mtype, "data": data})
        if mtype not in ("hello_request", "conn_request"):  # never log nonces / password hashes
            log.debug("> %s", txt[:300])
        await self.ws.send(txt)

    async def _recv_json(self, timeout):
        while True:
            try:
                raw = await asyncio.wait_for(self.ws.recv(), timeout)
            except websockets.ConnectionClosed:
                # The camera drops new controllers while it is still tearing down a previous session.
                raise BusyError("camera closed the connection (still releasing a previous session?)")
            if isinstance(raw, bytes):
                log.debug("< binary %d bytes (pre-auth, ignored)", len(raw))
                continue
            m = json.loads(raw)
            if m.get("type") != "hello_response":
                log.debug("< %s", raw[:300])
            return m.get("type"), m.get("data") or {}

    async def authenticate(self, timeout=15):
        client_nonce = os.urandom(32)
        await self.send("hello_request", {"info": self.info, "nonce": client_nonce.hex()})
        t, d = await self._recv_json(timeout)
        if t == "bye_message":
            raise BusyError(f"camera said bye: {d}")
        if t != "hello_response":
            raise AuthError(f"expected hello_response, got {t}")
        self.remote_info = d.get("info") or {}
        if d.get("status") == "busy":
            raise BusyError("camera is already being controlled")
        nonce = bytes.fromhex(d["nonce"])
        await self.send("conn_request", {"password": password_hash(self.password, nonce)})
        t, d = await self._recv_json(timeout)
        if t != "conn_response":
            raise AuthError(f"expected conn_response, got {t}")
        st = d.get("status")
        if st == "busy":
            raise BusyError("camera is busy")
        if st != "authorized":
            raise AuthError("wrong password")
        self.authorized = True
        log.info("authorized with %s", self.remote_info.get("device_name"))
        self._reader = asyncio.ensure_future(self._read_loop())

    async def _read_loop(self):
        try:
            async for raw in self.ws:
                if isinstance(raw, bytes):
                    await self.queue.put(("binary", raw))
                    continue
                log.debug("< %s", raw[:300])
                m = json.loads(raw)
                await self.queue.put((m.get("type"), m.get("data") or {}))
        except websockets.ConnectionClosed:
            pass
        finally:
            await self.queue.put(("closed", {}))

    async def close(self):
        if self.ws is not None:
            try:
                await self.ws.close()
            except Exception:
                pass
        if self._reader:
            self._reader.cancel()
