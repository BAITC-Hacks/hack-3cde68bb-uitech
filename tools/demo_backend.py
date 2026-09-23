"""Run the synthetic API demo. Python 3, standard library only."""
import argparse
import json
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]


def request(base, path, body=None):
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    req = Request(base.rstrip("/") + "/api/v1" + path, data=data,
                  headers={"Content-Type": "application/json"} if data else {})
    with urlopen(req, timeout=120) as response:
        raw = response.read()
        return json.loads(raw) if "application/json" in response.headers.get("Content-Type", "") else raw


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--url", default="http://127.0.0.1:8080")
    parser.add_argument("--fixture", default="01_baseline", choices=sorted(p.stem for p in (ROOT / "fixtures/contract").glob("*.json")))
    parser.add_argument("--approve", action="store_true", help="Approve and export this synthetic demo only")
    args = parser.parse_args()
    fixture = json.loads((ROOT / "fixtures/contract" / (args.fixture + ".json")).read_text(encoding="utf-8"))
    dataset = request(args.url, "/datasets", fixture["dataset"])
    calc_request = fixture["calculation"] | {"dataset_id": dataset["dataset_id"]}
    calc = request(args.url, "/calculations", calc_request)
    output = ROOT / ".local" / "demo"
    output.mkdir(parents=True, exist_ok=True)
    if args.approve:
        codes = sorted({warning["code"] for item in calc["items"] for warning in item["warnings"] + item["assumptions"]})
        calc = request(args.url, f'/calculations/{calc["calculation_id"]}/approve',
                       {"expected_revision": calc["revision"], "approved_by": "Synthetic demo reviewer", "acknowledged_issue_codes": codes})
        for group in calc["supplier_groups"]:
            supplier = group["supplier_id"]
            csv = request(args.url, f'/calculations/{calc["calculation_id"]}/export?supplier_id={supplier}&format=csv&revision={calc["revision"]}')
            (output / f"{args.fixture}-{supplier}.csv").write_bytes(csv)
    (output / f"{args.fixture}.json").write_text(json.dumps(calc, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"calculation_id": calc["calculation_id"], "status": calc["status"], "data_kind": calc["data_kind"],
                      "items": [{k: item[k] for k in ("product_id", "status", "recommended_purchase_qty", "first_stockout_date")} for item in calc["items"]],
                      "output": str(output)}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    try:
        main()
    except HTTPError as error:
        raise SystemExit(f"HTTP {error.code}: {error.read().decode('utf-8')}") from error
