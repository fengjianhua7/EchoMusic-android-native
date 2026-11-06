"""Redirect only six API24 imports to the API23 compatibility library.

Input must be the original official ARMv7 Node Mobile 18.20.4 binary.
Only ELF version metadata changes; instructions and relocations are unchanged.
"""
from pathlib import Path
import hashlib, struct, sys

source, destination = map(Path, sys.argv[1:3])
data = bytearray(source.read_bytes())
assert hashlib.sha256(data).hexdigest() == 'd60451f64718354b1a3d4857d20fbe56cc3a3dbe2c319c2911342aea2bbef92d'
assert data[:5] == b'\x7fELF\x01'
offset = struct.unpack_from('<I', data, 32)[0]
size, count = struct.unpack_from('<HH', data, 46)
sections = [struct.unpack_from('<10I', data, offset+i*size) for i in range(count)]
symbols = next(s for s in sections if s[1] == 11)
strings = sections[symbols[6]]
versions = next(s for s in sections if s[1] == 0x6fffffff)
needs = next(s for s in sections if s[1] == 0x6ffffffe)
def string(index):
    start = strings[4]+index
    return bytes(data[start:data.index(0,start)]).decode()
expected = {'getifaddrs','freeifaddrs','in6addr_any','pthread_barrier_init','pthread_barrier_destroy','pthread_barrier_wait'}
changed = set()
for i in range(symbols[5]//symbols[9]):
    name,_,_,_,_,section = struct.unpack_from('<IIIBBH',data,symbols[4]+i*symbols[9])
    version_offset = versions[4]+i*2
    if struct.unpack_from('<H',data,version_offset)[0] == 5:
        assert section == 0 and string(name) in expected
        struct.pack_into('<H',data,version_offset,1)
        changed.add(string(name))
assert changed == expected
position = needs[4]
while True:
    _,n,file,aux,nxt = struct.unpack_from('<HHIII',data,position)
    if string(file) == 'libc.so':
        assert n == 2
        first = position+aux
        _,_,index,_,next_aux = struct.unpack_from('<IHHII',data,first)
        second = first+next_aux
        assert index == 2 and string(struct.unpack_from('<IHHII',data,second)[3]) == 'LIBC_N'
        struct.pack_into('<H',data,position+2,1)
        struct.pack_into('<I',data,first+12,0)
        break
    assert nxt
    position += nxt
destination.write_bytes(data)
print('API23 version metadata patched for six backported imports:',hashlib.sha256(data).hexdigest())
