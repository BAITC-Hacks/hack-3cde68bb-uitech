"""Generate synthetic API acceptance examples, not a forecasting implementation."""
from copy import deepcopy
from datetime import date, timedelta
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'fixtures' / 'contract'
SOURCE = [{'source_id': 'synthetic_v1', 'sheet': None, 'cell_range': None}]


def sale(day, qty=10, product='P1', customer='CLIENT_REGULAR', suffix='regular'):
    return {
        'sale_id': f'{product}-{day}-{suffix}', 'product_id': product,
        'warehouse_id': 'WH_TEST', 'date': str(day),
        'document_id': f'DOC-{product}-{day}-{suffix}',
        'customer_id': customer, 'operation_type': 'sale',
        'quantity': qty, 'source_refs': deepcopy(SOURCE),
    }


def base():
    product = {
        'product_id': 'P1', 'supplier_id': 'SUP_A', 'sku_1c': '000001_',
        'supplier_article': 'TEST-001', 'name': 'Синтетический контрольный товар',
        'stock_unit': 'pcs', 'purchase_unit': 'pcs', 'purchase_unit_factor': 1,
        'category_id': 'STANDARD', 'moq_purchase_qty': 0,
        'pack_multiple_purchase_qty': 10, 'source_refs': deepcopy(SOURCE),
    }
    dataset = {
        'schema_version': '1.0', 'name': 'Synthetic acceptance fixture',
        'data_kind': 'synthetic', 'timezone': 'Asia/Almaty',
        'suppliers': [{'supplier_id': 'SUP_A', 'name': 'Вымышленный поставщик A'}],
        'products': [product],
        'sales_coverage': [{'product_id': 'P1', 'warehouse_id': 'WH_TEST',
                            'start_date': '2026-06-01', 'end_date_exclusive': '2026-07-01', 'complete': True}],
        'sales': [sale(date(2026, 6, day)) for day in range(1, 31)],
        'inventory': [{'product_id': 'P1', 'warehouse_id': 'WH_TEST', 'as_of': '2026-07-01',
                       'free_stock_qty': 40, 'source_refs': deepcopy(SOURCE)}],
        'inbound': [{'shipment_id': 'SHIP-1', 'product_id': 'P1', 'warehouse_id': 'WH_TEST',
                     'quantity': 20, 'expected_date': '2026-07-08', 'status': 'confirmed', 'source_refs': deepcopy(SOURCE)}],
        'inbound_coverage': [{'product_id': 'P1', 'warehouse_id': 'WH_TEST', 'as_of': '2026-07-01', 'complete': True}],
        'availability': [{'product_id': 'P1', 'warehouse_id': 'WH_TEST', 'start_date': '2026-06-01',
                          'end_date_exclusive': '2026-07-01', 'state': 'available',
                          'provenance': 'synthetic', 'source_refs': deepcopy(SOURCE)}],
        'seasonality_profiles': [{'profile_id': 'FLAT-A', 'scope_type': 'supplier', 'scope_id': 'SUP_A',
                                  'basis': 'daily_rate', 'indices': [1] * 12, 'source_refs': deepcopy(SOURCE)}],
        'sources': [{'source_id': 'synthetic_v1', 'kind': 'synthetic', 'role': 'acceptance_test',
                     'file_name': None, 'note': 'Полностью вымышленные данные; не данные Электрокомплекта.'}],
        'issues': [],
    }
    calculation = {
        'as_of': '2026-07-01', 'warehouse_id': 'WH_TEST', 'supplier_ids': [], 'category_ids': [],
        'history_start': '2026-06-01',
        'supplier_policies': [{'supplier_id': 'SUP_A', 'lead_time_days': 7, 'review_period_days': 7}],
        'category_policies': [{'category_id': 'STANDARD', 'safety_days': 7, 'purchasing_allowed': True}],
        'forecast': {'seasonality_mode': 'provided', 'growth_mode': 'manual', 'manual_growth_pct': 0,
                     'outlier_policy': 'robust_orders_v1', 'stockout_mode': 'confirmed_only'},
        'assumptions': [],
    }
    return {'fixture_version': '1.0', 'dataset': dataset, 'calculation': calculation, 'expected': {}}


CASES = {}


def save(name, case, expected):
    case['dataset']['name'] = name
    case['expected'] = expected
    CASES[name] = case


save('01_baseline', base(), {'product_id': 'P1', 'base_daily_rate': 10,
     'forecast_horizon_qty': 140, 'safety_stock_qty': 70, 'raw_order_stock_qty': 150,
     'recommended_purchase_qty': 150, 'first_stockout_date': '2026-07-05'})

for name, field, value in [('02_more_stock', 'inventory', 70), ('03_more_inbound', 'inbound', 50)]:
    c = base()
    c['dataset'][field][0]['free_stock_qty' if field == 'inventory' else 'quantity'] = value
    save(name, c, {'product_id': 'P1', 'raw_order_stock_qty': 120, 'recommended_purchase_qty': 120})

c = base()
c['calculation']['category_policies'].append({'category_id': 'CRITICAL', 'safety_days': 14, 'purchasing_allowed': True})
c['dataset']['products'][0]['category_id'] = 'CRITICAL'
save('04_category_policy', c, {'product_id': 'P1', 'safety_stock_qty': 140, 'recommended_purchase_qty': 220})

c = base()
c['calculation']['forecast']['manual_growth_pct'] = 20
save('05_growth_assumption', c, {'product_id': 'P1', 'forecast_horizon_qty': 168,
     'safety_stock_qty': 84, 'raw_order_stock_qty': 192, 'recommended_purchase_qty': 200})

c = base()
c['dataset']['seasonality_profiles'][0]['indices'] = [1, 1, 1, 1, 1, 1, 2, 1, .5, .5, 1, 1]
save('06_seasonal_peak', c, {'product_id': 'P1', 'base_daily_rate': 10,
     'forecast_horizon_qty': 280, 'safety_stock_qty': 140, 'recommended_purchase_qty': 360})

c = base()
c['dataset']['sales'] = c['dataset']['sales'][:20]
c['dataset']['availability'][0]['end_date_exclusive'] = '2026-06-21'
outage = deepcopy(c['dataset']['availability'][0])
outage.update(start_date='2026-06-21', end_date_exclusive='2026-07-01', state='stockout')
c['dataset']['availability'].append(outage)
save('07_confirmed_stockout', c, {'product_id': 'P1', 'base_daily_rate': 10,
     'lost_demand_estimate_qty': 100, 'recommended_purchase_qty': 150,
     'naive_raw_order_without_stockout_correction': 80})

c = base()
c['dataset']['sales'].append(sale(date(2026, 6, 15), 1000, customer='CLIENT_PROJECT', suffix='project'))
save('08_one_off_order', c, {'product_id': 'P1', 'compare_to': '01_baseline',
     'max_regular_forecast_relative_change': .05, 'excluded_document_ids': ['DOC-P1-2026-06-15-project'],
     'preserve_document_ids': ['DOC-P1-2026-06-15-regular']})

c = base()
split_sales = [sale(date(2026, 6, 15), 10, customer='CLIENT_PROJECT', suffix=f'split-{i:03}') for i in range(100)]
c['dataset']['sales'].extend(split_sales)
save('09_split_customer_order', c, {'product_id': 'P1', 'compare_to': '01_baseline',
     'max_regular_forecast_relative_change': .05, 'anomalous_customer_id': 'CLIENT_PROJECT',
     'excluded_document_ids': [s['document_id'] for s in split_sales],
     'preserve_document_ids': ['DOC-P1-2026-06-15-regular']})

c = base()
c['calculation']['history_start'] = '2026-01-01'
c['calculation']['forecast'].update(growth_mode='historical', manual_growth_pct=None)
c['dataset']['sales_coverage'][0]['start_date'] = '2026-01-01'
c['dataset']['availability'][0]['start_date'] = '2026-01-01'
c['dataset']['sales'] = []
day = date(2026, 1, 1)
while day < date(2026, 7, 1):
    c['dataset']['sales'].append(sale(day, day.month + 4))
    day += timedelta(days=1)
save('10_sustained_growth', c, {'product_id': 'P1', 'assertions': [
     'Forecast mean daily rate is at least 10 and exceeds full-history mean daily sales.',
     'The sustained multi-month rise is not excluded as a one-off event.'], 'excluded_document_ids': []})

c = base()
for s in c['dataset']['sales']:
    s['quantity'] = 20
save('11_more_sales', c, {'product_id': 'P1', 'base_daily_rate': 20, 'recommended_purchase_qty': 360})

c = base()
c['dataset']['inbound'][0]['expected_date'] = '2026-07-15'
save('12_inbound_outside_horizon', c, {'product_id': 'P1', 'eligible_inbound_qty': 0,
     'recommended_purchase_qty': 170, 'first_stockout_date': '2026-07-05'})

c = base()
c['dataset']['inventory'][0]['free_stock_qty'] = 300
save('13_no_order_when_sufficient', c, {'product_id': 'P1', 'recommended_purchase_qty': 0})

c = base()
c['dataset']['inventory'][0]['free_stock_qty'] = None
save('14_missing_stock', c, {'product_id': 'P1', 'status': 'NEEDS_INPUT',
     'recommended_purchase_qty': None, 'approval_http_status': 422})

c = base()
p2 = deepcopy(c['dataset']['products'][0])
p2.update(product_id='P2', supplier_id='SUP_B', sku_1c='000002_', supplier_article='TEST-002')
c['dataset']['products'].append(p2)
c['dataset']['suppliers'].append({'supplier_id': 'SUP_B', 'name': 'Вымышленный поставщик B'})
for key in ['sales_coverage', 'inventory', 'inbound_coverage', 'availability']:
    row = deepcopy(c['dataset'][key][0]); row['product_id'] = 'P2'
    if key == 'inventory': row['free_stock_qty'] = 0
    c['dataset'][key].append(row)
c['dataset']['sales'].extend(sale(date(2026, 6, day), 6, product='P2') for day in range(1, 31))
profile = deepcopy(c['dataset']['seasonality_profiles'][0]); profile.update(profile_id='FLAT-B', scope_id='SUP_B')
c['dataset']['seasonality_profiles'].append(profile)
c['calculation']['supplier_policies'].append({'supplier_id': 'SUP_B', 'lead_time_days': 7, 'review_period_days': 7})
save('15_supplier_groups', c, {'supplier_groups': {'SUP_A': ['P1'], 'SUP_B': ['P2']},
     'recommended_purchase_qty_by_product': {'P1': 150, 'P2': 130},
     'every_item_has_explanation': True})

c = base()
repeated = [sale(date(2026, 6, d), 100, customer='CLIENT_WHOLESALE', suffix='weekly') for d in [1, 8, 15, 22, 29]]
c['dataset']['sales'].extend(repeated)
save('16_repeated_large_orders', c, {'product_id': 'P1',
     'preserve_document_ids': [s['document_id'] for s in repeated],
     'assertions': ['Recurring weekly purchases remain in the regular-demand series.']})

c = base()
c['dataset']['products'][0].update(purchase_unit='box', purchase_unit_factor=12,
                                   moq_purchase_qty=5, pack_multiple_purchase_qty=5)
save('17_purchase_unit_conversion', c, {'product_id': 'P1', 'raw_order_stock_qty': 150,
     'recommended_purchase_qty': 15, 'recommended_stock_qty': 180})

DEST.mkdir(parents=True, exist_ok=True)
for name, case in CASES.items():
    (DEST / f'{name}.json').write_text(json.dumps(case, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
print(f'Generated {len(CASES)} synthetic contract fixtures in {DEST}')
