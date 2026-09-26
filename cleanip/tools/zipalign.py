#!/usr/bin/env python3
"""Minimal zipalign: rewrite a zip so every entry's data starts on a 4 byte
boundary - what Android wants before an APK gets signed."""
import struct
import sys
import zipfile
import zlib

LFH = 0x04034b50
CDH = 0x02014b50
EOCD = 0x06054b50


def dos_time(dt):
    year, month, day, hour, minute, second = dt
    if year < 1980:
        year = 1980
    return ((hour << 11) | (minute << 5) | (second // 2),
            ((year - 1980) << 9) | (month << 5) | day)


def align(src, dst, alignment=4):
    with zipfile.ZipFile(src, 'r') as zin:
        blobs = []
        for zi in zin.infolist():
            blobs.append((zi, zin.read(zi.filename)))

    with open(dst, 'wb') as f:
        central = []
        for zi, data in blobs:
            name = zi.filename.encode('utf-8')
            crc = zlib.crc32(data) & 0xffffffff
            method = zi.compress_type
            if method == zipfile.ZIP_DEFLATED:
                co = zlib.compressobj(9, zlib.DEFLATED, -15)
                payload = co.compress(data) + co.flush()
            else:
                method = 0
                payload = data
            offset = f.tell()
            pad = (alignment - ((offset + 30 + len(name)) % alignment)) % alignment
            extra = b'\x00' * pad
            csize = len(payload)
            usize = len(data)
            t, d = dos_time(zi.date_time)
            f.write(struct.pack('<IHHHHHIIIHH', LFH, 20, 0, method, t, d,
                                crc, csize, usize, len(name), len(extra)))
            f.write(name)
            f.write(extra)
            f.write(payload)
            central.append((zi, name, extra, offset, crc, csize, usize, method, t, d))

        cd_start = f.tell()
        for zi, name, extra, offset, crc, csize, usize, method, t, d in central:
            f.write(struct.pack('<IHHHHHHIIIHHHHHII', CDH, 20, 20, 0, method, t, d,
                                crc, csize, usize, len(name), len(extra), 0, 0, 0,
                                zi.external_attr, offset))
            f.write(name)
            f.write(extra)
        cd_end = f.tell()
        f.write(struct.pack('<IHHHHIIH', EOCD, 0, 0, len(central), len(central),
                            cd_end - cd_start, cd_start, 0))

    # make sure we produced something readable
    with zipfile.ZipFile(dst, 'r') as z:
        bad = z.testzip()
        if bad is not None:
            raise SystemExit('zipalign produced a corrupt entry: %s' % bad)


def main():
    if len(sys.argv) != 3:
        print('usage: zipalign.py in.apk out.apk')
        return 1
    align(sys.argv[1], sys.argv[2])
    return 0


if __name__ == '__main__':
    sys.exit(main())
