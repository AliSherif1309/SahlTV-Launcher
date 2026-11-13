"""Check runtime classes in the actual APK; instrumentation dependencies must not mask omissions."""
import struct
import sys
import zipfile

classes = set()
with zipfile.ZipFile(sys.argv[1]) as apk:
    for name in apk.namelist():
        if not name.endswith('.dex'):
            continue
        data = apk.read(name)
        def uint(offset):
            return struct.unpack_from('<I', data, offset)[0]
        def string(index):
            offset = uint(uint(60) + 4 * index)
            while data[offset] & 128:
                offset += 1
            offset += 1
            return data[offset:data.index(0, offset)].decode('utf-8', errors='replace')
        for index in range(uint(96)):
            type_index = uint(uint(100) + 32 * index)
            classes.add(string(uint(uint(68) + 4 * type_index)))

required = {
    'Landroidx/core/content/ContextCompat;',
    'Landroidx/core/app/ActivityCompat;',
    'Landroidx/fragment/app/Fragment;',
    'Lcom/aly/sahlconnect/ConnectScannerActivity;',
}
missing = required - classes
if missing:
    sys.exit('Missing scanner runtime classes: ' + ', '.join(sorted(missing)))
print('Scanner runtime classes present in APK')
