# Writes the FileDescriptorSet of LiveKit's FFI protocol, taken from the descriptors compiled into the official Python SDK wheel.
# Usage: python3 -I livekit_ffi_descriptors.py <unpacked wheel dir> <out.desc>
import sys, importlib, pkgutil
sys.path.insert(0, sys.argv[1])
from google.protobuf import descriptor_pb2
import livekit.rtc._proto as P
fds = descriptor_pb2.FileDescriptorSet(); seen=set()
def add(fd):
    if fd.name in seen: return
    for d in fd.dependencies: add(d)
    seen.add(fd.name); p = descriptor_pb2.FileDescriptorProto(); fd.CopyToProto(p); fds.file.append(p)
for m in pkgutil.iter_modules(P.__path__):
    if m.name.endswith('_pb2'):
        mod = importlib.import_module('livekit.rtc._proto.' + m.name); add(mod.DESCRIPTOR)
open(sys.argv[2], 'wb').write(fds.SerializeToString())
