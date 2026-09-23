"""Read-only check immediately after migrating the legacy JSON directory to PostgreSQL."""
import argparse
import json
from decimal import Decimal
from pathlib import Path
from urllib.request import urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://127.0.0.1:8080')
    parser.add_argument('--directory', type=Path, default=Path(__file__).resolve().parents[1] / '.local/backend-data')
    args = parser.parse_args()

    def get(path):
        with urlopen(args.url.rstrip('/') + '/api/v1' + path, timeout=120) as response:
            return json.load(response, parse_float=Decimal)

    assert get('/health')['storage'] == 'postgres', 'Backend is not using PostgreSQL'
    saved = {d['dataset_id']: d for d in get('/datasets')}
    source_ids = {p.stem for p in args.directory.glob('ds_*.json')}
    assert source_ids <= saved.keys(), 'Some legacy datasets are missing'
    checked = 0
    for file in args.directory.glob('calc_*.json'):
        old = json.loads(file.read_text(encoding='utf-8'), parse_float=Decimal)
        assert get('/calculations/' + file.stem) == old, 'Calculation changed: ' + file.stem
        checked += 1
    for supplier in ('iek', 'systeme'):
        summary_file = args.directory.parent / (supplier + '-import-summary.json')
        if summary_file.exists():
            old = json.loads(summary_file.read_text(encoding='utf-8'))
            current = saved[old['dataset_id']]
            for name, count in old['counts'].items():
                assert current['counts'][name] == count, f'{supplier}: {name} count differs'
    print(json.dumps({'storage': 'postgres', 'legacy_datasets_verified': len(source_ids),
                      'legacy_calculations_verified': checked, 'original_json_retained': True}, indent=2))


if __name__ == '__main__':
    main()
