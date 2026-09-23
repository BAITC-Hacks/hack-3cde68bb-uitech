"""Upload six Systeme or IEK XLSX files from the supplied archive. Python >= 3.11."""
import argparse
import json
from pathlib import Path
import uuid
from urllib.request import Request, urlopen
from urllib.error import HTTPError
import zipfile

PREFIX_ROLES = {"MOQ": "moq", "Динамика": "sales_transactions", "Ежемесячные остатки": "stock_monthly",
                "Ежемесячные продажи": "sales_monthly", "Сезонность": "seasonality", "Товар в пути": "inventory_transit", "Путь": "inventory_transit"}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--supplier", choices=("SYSTEME", "IEK"), default="SYSTEME")
    args = parser.parse_args()
    files = {}
    with zipfile.ZipFile(args.archive, metadata_encoding="cp866") as archive:
        selected = [entry for entry in archive.infolist() if entry.filename.endswith(".xlsx")]
        if len(selected) != 6 or sum(entry.file_size for entry in selected) > 35 * 1024 * 1024:
            raise SystemExit("Expected six XLSX files totaling at most 35 MB")
        for entry in selected:
            name = Path(entry.filename).name
            role = next((r for p, r in PREFIX_ROLES.items() if name.startswith(p)), None)
            if role is None or role in files or entry.file_size > 15 * 1024 * 1024:
                raise SystemExit("Unexpected or duplicate file: " + name)
            files[role] = (name, archive.read(entry))
    name = "IEK" if args.supplier == "IEK" else "Systeme Electric"
    manifest = {"name": name + " 22.09.2026", "data_kind": "real", "timezone": "Asia/Almaty",
                "files": [{"part_name": role, "supplier_id": args.supplier, "role": role} for role in sorted(files)]}
    boundary = "uitech" + uuid.uuid4().hex
    chunks = [f'--{boundary}\r\nContent-Disposition: form-data; name="manifest"\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n'.encode(),
              json.dumps(manifest, ensure_ascii=False).encode("utf-8"), b"\r\n"]
    for role, (name, data) in files.items():
        chunks.extend([f'--{boundary}\r\nContent-Disposition: form-data; name="{role}"; filename="{name}"\r\nContent-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet\r\n\r\n'.encode("utf-8"), data, b"\r\n"])
    chunks.append(f"--{boundary}--\r\n".encode())
    req = Request(args.url.rstrip("/") + "/api/v1/datasets/import", data=b"".join(chunks), headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urlopen(req, timeout=180) as response:
        result = json.load(response)
    output = Path(__file__).resolve().parents[1] / ".local" / (args.supplier.lower() + "-import-summary.json")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({key: result[key] for key in ("dataset_id", "status", "reused", "counts", "issue_count")}, ensure_ascii=False, indent=2))
    print("Full summary:", output)


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"HTTP {error.code}: {error.read().decode('utf-8')}") from error
