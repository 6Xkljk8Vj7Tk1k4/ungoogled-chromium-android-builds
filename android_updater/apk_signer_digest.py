#!/usr/bin/env python3
"""Wypisuje SHA-256 certyfikatu, którym podpisano APK (schematy v2/v3).

To ta sama wartość, którą pokazuje `apksigner verify --print-certs`, ale bez
Javy i bez Android SDK: skrypt sam czyta blok podpisu APK ("APK Signing
Block"), który leży tuż przed katalogiem centralnym archiwum ZIP.

    python3 apk_signer_digest.py plik.apk

Kod wyjścia 0 i jeden odcisk w linii (małe litery, bez dwukropków); gdy APK
ma kilku podpisujących albo różne certyfikaty w v2 i v3 -- po jednym w linii.
Kod 1, gdy pliku nie da się odczytać albo nie ma podpisu v2/v3.

UWAGA: skrypt tylko ODCZYTUJE certyfikat, nie sprawdza poprawności podpisu.
To robi Android przy instalacji (albo `apksigner verify`).
"""
import hashlib
import struct
import sys

MAGIC = b"APK Sig Block 42"
# Identyfikatory bloków: v2, v3, v3.1.
SCHEME_IDS = {0x7109871A, 0xF05368C0, 0x1B93AD61}


def _take(data, pos):
    """Zwraca (element poprzedzony 32-bitową długością, pozycja za nim)."""
    if pos + 4 > len(data):
        raise ValueError("uszkodzony blok podpisu")
    (size,) = struct.unpack_from("<I", data, pos)
    pos += 4
    if pos + size > len(data):
        raise ValueError("uszkodzony blok podpisu")
    return data[pos:pos + size], pos + size


def _chunks(data):
    """Dzieli ciąg złożony WYŁĄCZNIE z elementów poprzedzonych długością."""
    pos = 0
    while pos < len(data):
        chunk, pos = _take(data, pos)
        yield chunk


def signer_digests(path):
    with open(path, "rb") as f:
        f.seek(0, 2)
        file_size = f.tell()
        # Koniec katalogu centralnego (EOCD): 22 bajty + komentarz do 65535.
        tail_size = min(file_size, 22 + 65535)
        f.seek(file_size - tail_size)
        tail = f.read(tail_size)
        eocd = tail.rfind(b"PK\x05\x06")
        if eocd < 0:
            raise ValueError("to nie jest archiwum ZIP")
        (cd_offset,) = struct.unpack_from("<I", tail, eocd + 16)
        if cd_offset < 32:
            raise ValueError("brak bloku podpisu APK")
        f.seek(cd_offset - 24)
        footer = f.read(24)
        if footer[8:] != MAGIC:
            raise ValueError("brak bloku podpisu APK (tylko podpis v1 albo brak podpisu)")
        (block_size,) = struct.unpack_from("<Q", footer, 0)
        if block_size < 24 or block_size > cd_offset:
            raise ValueError("uszkodzony blok podpisu")
        f.seek(cd_offset - block_size)
        pairs = f.read(block_size - 24)

    digests = []
    pos = 0
    while pos + 12 <= len(pairs):
        size, scheme_id = struct.unpack_from("<QI", pairs, pos)
        value = pairs[pos + 12:pos + 8 + size]
        pos += 8 + size
        if scheme_id not in SCHEME_IDS:
            continue
        # wartość -> lista podpisujących -> podpisujący -> dane podpisane ->
        # skróty, potem certyfikaty. Dalsze pola różnią się między v2 i v3
        # (v3 ma tam liczby bez prefiksu długości), więc czytamy tylko początek.
        signers, _ = _take(value, 0)
        for signer in _chunks(signers):
            signed_data, _ = _take(signer, 0)
            _, pos_after_digests = _take(signed_data, 0)
            certificates, _ = _take(signed_data, pos_after_digests)
            certificate, _ = _take(certificates, 0)
            digest = hashlib.sha256(certificate).hexdigest()
            if digest not in digests:
                digests.append(digest)
    if not digests:
        raise ValueError("brak podpisu v2/v3")
    return digests


def main():
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    try:
        for digest in signer_digests(sys.argv[1]):
            print(digest)
    except (OSError, ValueError, struct.error) as e:
        sys.exit(f"BŁĄD: {sys.argv[1]}: {e}")


if __name__ == "__main__":
    main()
