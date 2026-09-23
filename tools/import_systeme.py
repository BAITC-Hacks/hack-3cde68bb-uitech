"""Backward-compatible entry point for the Systeme importer."""
from urllib.error import HTTPError
from import_supplier import main

if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"HTTP {error.code}: {error.read().decode('utf-8')}") from error
