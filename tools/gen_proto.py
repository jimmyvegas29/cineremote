"""Compile cineremote/proto/*.proto into cineremote/proto/schema.pb (a FileDescriptorSet).

The .proto files are this project's own description of the camera app's control protocol, written
for interoperability. The app loads the compiled descriptor set at runtime, so users don't need protoc.
Run after editing a .proto file:

    pip install grpcio-tools
    python tools/gen_proto.py
"""
import glob
import os
import sys

from grpc_tools import protoc

HERE = os.path.dirname(os.path.abspath(__file__))
PROTO = os.path.normpath(os.path.join(HERE, "..", "cineremote", "proto"))
OUT = os.path.join(PROTO, "schema.pb")

files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(PROTO, "*.proto")))
rc = protoc.main(["protoc", f"-I{PROTO}", f"--descriptor_set_out={OUT}", "--include_imports"] + files)
if rc != 0:
    sys.exit(f"protoc failed ({rc})")
print(f"wrote {OUT} from {', '.join(files)}")
