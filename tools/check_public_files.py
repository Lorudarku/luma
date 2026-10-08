#!/usr/bin/env python3
"""Revisa candidatos públicos sin mostrar secretos. Solo lectura; no usa servicios externos."""
import argparse
import re
import subprocess
import tempfile
from pathlib import Path

PATTERNS = {
    "clave privada": re.compile(rb"-----BEGIN (?:RSA |EC |OPENSSH |DSA |ENCRYPTED )?PRIVATE KEY-----"),
    "token GitHub": re.compile(rb"\b(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{50,})\b"),
    "credencial AWS": re.compile(rb"\b(?:AKIA|ASIA)[A-Z0-9]{16}\b"),
    "token Google": re.compile(rb"\bAIza[A-Za-z0-9_-]{35}\b"),
    "token Slack": re.compile(rb"\bxox[baprs]-[A-Za-z0-9-]{20,}\b"),
    "URL con credenciales": re.compile(rb"https?://[^\s/:]+:[^\s/@]+@"),
    "ruta personal": re.compile(rb"(?:/home" + rb"/[^/\s\"']+/|/Users" + rb"/[^/\s\"']+/|[A-Z]:\\Users\\[^\\\s]+\\)"),
    "identificador de dispositivo": re.compile(rb"(?:serialNumber|deviceSerial|device_id)\s*[=:]\s*[\"']?[A-Za-z0-9-]{8,}", re.I),
    "asignación de secreto": re.compile(rb"(?:api[_-]?key|access[_-]?token|client[_-]?secret|storePassword|keyPassword)\s*[=:]\s*[\"'][A-Za-z0-9_+/=-]{12,}[\"']", re.I),
}
PRIVATE_SUFFIXES = {".jks", ".keystore", ".p12", ".pfx", ".pem", ".key", ".db", ".sqlite", ".sqlite3", ".lumabackup", ".hprof", ".heapdump"}
PRIVATE_NAMES = {"local.properties", "signing.properties", "secrets.properties", ".env"}
PRIVATE_PARTS = {".idea", ".kotlin", ".gradle", ".aws", ".codex", ".agents", "build", "captures", "graphify-out", ".graphify-venv"}


def candidates(root: Path) -> list[str]:
    """Devuelve archivos rastreados y candidatos no ignorados; usa un índice temporal si no hay Git válido."""
    args = ["ls-files", "--cached", "--others", "--exclude-standard", "-z"]
    result = subprocess.run(["git", "-C", str(root), *args], capture_output=True)
    if result.returncode:
        # 1. No inicializar ni cambiar el repositorio del usuario para revisar su selección pública.
        with tempfile.TemporaryDirectory(prefix="luma-public-index-") as directory:
            subprocess.run(["git", "init", "--bare", "--quiet", directory], check=True, capture_output=True)
            result = subprocess.run(["git", f"--git-dir={directory}", f"--work-tree={root}", *args], check=True, capture_output=True)
    return sorted(set(result.stdout.decode("utf-8", errors="strict").rstrip("\0").split("\0")) - {""})


def findings(root: Path, names: list[str]) -> list[str]:
    """Examina rutas y bytes, incluidos metadatos; devuelve solo ubicación/tipo, nunca el valor encontrado."""
    found = []
    for name in names:
        relative = Path(name)
        path = root / relative
        # 1. Rechazar artefactos privados incluso si alguien los añadió con git add -f.
        private_environment = relative.name.startswith(".env.") and relative.name != ".env.example"
        if private_environment or relative.suffix.lower() in PRIVATE_SUFFIXES or relative.name in PRIVATE_NAMES or PRIVATE_PARTS.intersection(relative.parts):
            found.append(f"{name}: archivo privado o generado")
        if path.is_symlink():
            found.append(f"{name}: enlace simbólico; revisar destino antes de publicar")
            continue
        if not path.is_file():
            continue
        if path.stat().st_size > 10 * 1024 * 1024:
            found.append(f"{name}: archivo >10 MiB; revisión manual necesaria")
            continue
        data = path.read_bytes()
        # 2. No incluir fragmentos del contenido: las salidas son seguras para logs de CI.
        for label, pattern in PATTERNS.items():
            for match in pattern.finditer(data):
                line = data.count(b"\n", 0, match.start()) + 1
                found.append(f"{name}:{line}: {label}")
    return found


def main() -> int:
    """Ejecuta la revisión local/CI; entrada --root opcional; salida 1 si hay hallazgos y 0 si no."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    root = parser.parse_args().root.resolve()
    names = candidates(root)
    found = findings(root, names)
    for item in found:
        print(item)
    print(f"Revisados {len(names)} archivos candidatos; {len(found)} hallazgos. Los valores se ocultan.")
    return int(bool(found))


if __name__ == "__main__":
    raise SystemExit(main())
