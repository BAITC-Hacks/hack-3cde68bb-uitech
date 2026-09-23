import { useState } from 'react';
import type { Dataset, Params } from './types';
import { Icon } from './ui';

export function initialParams(dataset: Dataset, id: string): Params {
  const asOf = dataset.inventory[0]?.as_of || new Date().toISOString().slice(0, 10);
  const start = new Date(asOf + 'T12:00:00Z');
  start.setUTCMonth(start.getUTCMonth() - 6);
  return {
    dataset_id: id,
    as_of: asOf,
    warehouse_id:
      dataset.inventory[0]?.warehouse_id || dataset.sales_coverage[0]?.warehouse_id || '',
    history_start: start.toISOString().slice(0, 10),
    supplier_ids: [],
    category_ids: [],
    supplier_policies: dataset.suppliers.map((s) => ({
      supplier_id: s.supplier_id,
      lead_time_days: null,
      review_period_days: null,
    })),
    category_policies: [
      ...new Set(dataset.products.map((p) => p.category_id).filter((c): c is string => !!c)),
    ]
      .sort()
      .map((category_id) => ({ category_id, safety_days: null, purchasing_allowed: true })),
    forecast: {
      seasonality_mode: 'provided',
      growth_mode: 'manual',
      manual_growth_pct: 0,
      outlier_policy: 'robust_orders_v1',
      stockout_mode: 'confirmed_only',
    },
    assumptions: [],
  };
}
export default function Settings({
  dataset,
  value,
  busy,
  submit,
}: {
  dataset: Dataset;
  value: Params;
  busy: boolean;
  submit: (p: Params) => void;
}) {
  const [p, set] = useState<Params>(() => structuredClone(value));
  const warehouses = [
    ...new Set([...dataset.inventory, ...dataset.sales_coverage].map((s) => s.warehouse_id)),
  ];
  const nullable = (v: string) => (v === '' ? null : Number(v));
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        submit(p);
      }}
    >
      <fieldset disabled={busy} className="modal-body settings">
        <p className="muted">
          Параметры определяют горизонт и запас. Сроки поставки и правила категорий задаёт менеджер.
        </p>
        <div className="form-grid three">
          <label>
            На начало дня
            <input
              type="date"
              required
              value={p.as_of}
              onChange={(e) => set({ ...p, as_of: e.target.value })}
            />
          </label>
          <label>
            История с
            <input
              type="date"
              required
              max={p.as_of}
              value={p.history_start}
              onChange={(e) => set({ ...p, history_start: e.target.value })}
            />
          </label>
          <label>
            Склад
            <select
              required
              value={p.warehouse_id}
              onChange={(e) => set({ ...p, warehouse_id: e.target.value })}
            >
              {warehouses.map((w) => (
                <option key={w}>{w}</option>
              ))}
            </select>
          </label>
        </div>
        <h3>
          Поставщики <small>дни</small>
        </h3>
        <div className="policy-table">
          <div className="policy-heading">
            <span>Поставщик</span>
            <span>Срок поставки</span>
            <span>Период заказа</span>
          </div>
          {p.supplier_policies.map((policy, i) => (
            <div className="policy-row" key={policy.supplier_id}>
              <strong>
                {dataset.suppliers.find((s) => s.supplier_id === policy.supplier_id)?.name ||
                  policy.supplier_id}
              </strong>
              <input
                aria-label={`Срок поставки ${policy.supplier_id}`}
                required
                type="number"
                min="0"
                max="365"
                placeholder="Укажите"
                value={policy.lead_time_days ?? ''}
                onChange={(e) =>
                  set({
                    ...p,
                    supplier_policies: p.supplier_policies.map((s, j) =>
                      j === i ? { ...s, lead_time_days: nullable(e.target.value) } : s,
                    ),
                  })
                }
              />
              <input
                aria-label={`Период заказа ${policy.supplier_id}`}
                required
                type="number"
                min="1"
                max="366"
                placeholder="Укажите"
                value={policy.review_period_days ?? ''}
                onChange={(e) =>
                  set({
                    ...p,
                    supplier_policies: p.supplier_policies.map((s, j) =>
                      j === i ? { ...s, review_period_days: nullable(e.target.value) } : s,
                    ),
                  })
                }
              />
            </div>
          ))}
        </div>
        <h3>Политики категорий</h3>
        {dataset.products.some((product) => !product.category_id) && (
          <p className="notice amber">
            Есть товары без категории. Они останутся со статусом «Нужны данные».
          </p>
        )}
        <div className="policy-table">
          <div className="policy-heading">
            <span>Категория</span>
            <span>Страховой запас, дни</span>
            <span>Закупка</span>
          </div>
          {p.category_policies.map((policy, i) => (
            <div className="policy-row" key={policy.category_id}>
              <strong>{policy.category_id}</strong>
              <input
                aria-label={`Страховой запас ${policy.category_id}`}
                required
                type="number"
                min="0"
                max="366"
                placeholder="Укажите"
                value={policy.safety_days ?? ''}
                onChange={(e) =>
                  set({
                    ...p,
                    category_policies: p.category_policies.map((s, j) =>
                      j === i ? { ...s, safety_days: nullable(e.target.value) } : s,
                    ),
                  })
                }
              />
              <label className="check-label">
                <input
                  type="checkbox"
                  checked={policy.purchasing_allowed}
                  onChange={(e) =>
                    set({
                      ...p,
                      category_policies: p.category_policies.map((s, j) =>
                        j === i ? { ...s, purchasing_allowed: e.target.checked } : s,
                      ),
                    })
                  }
                />
                Разрешена
              </label>
            </div>
          ))}
        </div>
        <h3>Прогноз спроса</h3>
        <div className="form-grid three">
          <label>
            Сезонность
            <select
              value={p.forecast.seasonality_mode}
              onChange={(e) =>
                set({
                  ...p,
                  forecast: {
                    ...p.forecast,
                    seasonality_mode: e.target.value as 'provided' | 'estimate',
                  },
                })
              }
            >
              <option value="provided">Из данных</option>
              <option value="estimate">Оценить по истории</option>
            </select>
          </label>
          <label>
            Рост спроса
            <select
              value={p.forecast.growth_mode}
              onChange={(e) =>
                set({
                  ...p,
                  forecast: {
                    ...p.forecast,
                    growth_mode: e.target.value as 'manual' | 'historical',
                    manual_growth_pct: e.target.value === 'manual' ? 0 : null,
                  },
                })
              }
            >
              <option value="manual">Задать вручную</option>
              <option value="historical">Тренд по истории</option>
            </select>
          </label>
          {p.forecast.growth_mode === 'manual' && (
            <label>
              Изменение, %
              <input
                type="number"
                required
                min="-100"
                max="10000"
                step="any"
                value={p.forecast.manual_growth_pct ?? ''}
                onChange={(e) =>
                  set({
                    ...p,
                    forecast: { ...p.forecast, manual_growth_pct: nullable(e.target.value) },
                  })
                }
              />
            </label>
          )}
        </div>
        <p className="muted small">
          Для оценки сезонности нужны два полных годовых цикла, для тренда — минимум три полных
          месяца. Неизвестные остатки и наличие автоматически не заполняются.
        </p>
        {!!p.assumptions.length && (
          <div className="notice amber">
            В этом сценарии уже есть {p.assumptions.length} допущений. Они сохранятся в расчёте и
            потребуют подтверждения при утверждении.
          </div>
        )}
        <button className="primary full" type="submit">
          <Icon name="trend" />
          Рассчитать рекомендации
        </button>
      </fieldset>
    </form>
  );
}
