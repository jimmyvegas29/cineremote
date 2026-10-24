"""Protobuf message classes for the camera app's control protocol (package cineremote.camctl.v5).

The schema lives in proto/*.proto (this project's description of the protocol, field numbers matching
the Blackmagic Camera app, protocol 5.0). tools/gen_proto.py compiles it into proto/schema.pb, which is
loaded here, so running the app needs no protoc. Package names never travel on the wire.
"""
import os

from google.protobuf import descriptor_pb2, descriptor_pool, message_factory

_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "proto")

_pool = descriptor_pool.DescriptorPool()
_set = descriptor_pb2.FileDescriptorSet()
with open(os.path.join(_DIR, "schema.pb"), "rb") as _f:
    _set.ParseFromString(_f.read())
for _fd in _set.file:          # protoc emits dependencies first
    _pool.Add(_fd)

PKG = "cineremote.camctl.v5"


def msg(name):
    """Return the generated class for message `name` (short name inside the v5 package)."""
    return message_factory.GetMessageClass(_pool.FindMessageTypeByName(f"{PKG}.{name}"))


def enum(name):
    return _pool.FindEnumTypeByName(f"{PKG}.{name}")


def enum_value(enum_name, value_name):
    return enum(enum_name).values_by_name[value_name].number


def enum_name(enum_name_, number):
    v = enum(enum_name_).values_by_number.get(number)
    return v.name if v else str(number)


ControllerMessage = msg("ControllerMessage")
ControllerMessages = msg("ControllerMessages")
SubordinateMessage = msg("SubordinateMessage")
SubordinateMessages = msg("SubordinateMessages")
Property = msg("Property")
AppInfo = msg("AppInfo")

# PropertyId enum value -> Property oneof field name (they share numbers in the schema)
PROPERTY_FIELD = {f.number: f.name for f in Property.DESCRIPTOR.oneofs_by_name["value"].fields}
PROPERTY_IDS = {v.name: v.number for v in enum("PropertyId").values}


def prop_id(short):
    """prop_id('CAM_SHUTTER') -> int"""
    return PROPERTY_IDS["PROPERTY_ID_" + short.upper()]
