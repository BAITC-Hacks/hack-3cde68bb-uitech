import { useState } from 'react';
import type { Item, Product } from './types';
import { Badge, Chart, date, fmt, Icon, issueMessage } from './ui';

export default function ItemDetail({
  item,
  product,
  busy,
  edit,
  correctSource,
}: {
  item: Item;
  product?: Product;
  busy: boolean;
  edit: (qty: number | null, reason: string) => void;
  correctSource: () => void;
}) {
  const [quantity, setQuantity] = useState(
    item.final_purchase_qty == null ? '' : String(item.final_purchase_qty),
  );
  const [reason, setReason] = useState('');
  const blocked = item.status === 'NEEDS_INPUT';
  return (
    <div className="modal-body detail">
      <div className="detail-identity">
        <div>
          <span className="eyebrow">{item.supplier_article || item.sku_1c}</span>
          <h3>{item.name}</h3>
          <p className="muted">
            Код 1С: {item.sku_1c} · {item.category_id || 'Категория не указана'}
          </p>
        </div>
        <Badge status={item.status} />
      </div>
      <div className="detail-summary">
        <div>
          <span>Рекомендация</span>
          <strong>
            {fmt(item.recommended_purchase_qty)} <small>{item.purchase_unit}</small>
          </strong>
        </div>
        <div>
          <span>К заказу</span>
          <strong>
            {fmt(item.final_purchase_qty)} <small>{item.purchase_unit}</small>
          </strong>
        </div>
        <div>
          <span>Первый риск дефицита</span>
          <strong className="date-value">{date(item.first_stockout_date)}</strong>
        </div>
      </div>
      <button className="secondary" disabled={busy} onClick={correctSource}>
        Уточнить исходные данные товара
      </button>
      <section>
        <h3>Как получено количество</h3>
        <p className={`explanation ${blocked ? 'blocked' : ''}`}>
          {blocked
            ? 'Расчёт приостановлен до уточнения данных. Проверьте список ниже.'
            : item.explanation}
        </p>
        {!blocked && (
          <>
            <div className="formula-grid">
              {[
                ['forecast_horizon_qty', 'Спрос на горизонт'],
                ['safety_stock_qty', 'Страховой запас'],
                ['free_stock_qty', 'Свободный остаток'],
                ['eligible_inbound_qty', 'Ожидаемые поставки'],
              ].map(([key, title], i) => (
                <div key={key}>
                  <span>{title}</span>
                  <strong>
                    {i === 1 ? '+ ' : i > 1 ? '− ' : ''}
                    {fmt(item.factors[key])}
                    <small> {item.stock_unit}</small>
                  </strong>
                </div>
              ))}
            </div>
            <p className="muted small">
              Потребность до округления: {fmt(item.factors.raw_order_stock_qty)} {item.stock_unit}.
              MOQ: {fmt(product?.moq_purchase_qty)} · кратность:{' '}
              {fmt(product?.pack_multiple_purchase_qty)} {item.purchase_unit} · единиц хранения в
              закупке: {fmt(product?.purchase_unit_factor)}.
            </p>
          </>
        )}
      </section>
      {!blocked && (
        <>
          <section>
            <div className="section-title">
              <h3>Продажи и регулярный спрос</h3>
              <span className="muted small">{item.stock_unit} / день</span>
            </div>
            <Chart
              label="Исторические продажи, очищенный и упущенный спрос"
              rows={item.history}
              lines={[
                { key: 'actual_sales_qty', name: 'Фактические продажи', color: '#a9b2bd' },
                { key: 'regular_sales_qty', name: 'Регулярный спрос', color: '#21755d' },
                { key: 'estimated_lost_qty', name: 'Оценка упущенного спроса', color: '#d89940' },
              ]}
            />
          </section>
          <section>
            <h3>Прогноз наличия</h3>
            <Chart
              label="Прогноз спроса и остатка с существующими поставками"
              rows={item.forecast}
              lines={[
                { key: 'demand_qty', name: 'Прогноз спроса', color: '#6d84b8' },
                { key: 'projected_stock_qty', name: 'Остаток на конец дня', color: '#21755d' },
              ]}
            />
            <p className="muted small">
              Новый заказ ещё не включён в поступления. Прогноз учитывает только существующий
              транзит.
            </p>
          </section>
        </>
      )}
      {!!item.anomalies.length && (
        <section>
          <h3>
            Исключённые всплески <span className="count">{item.anomalies.length}</span>
          </h3>
          <div className="anomalies">
            {item.anomalies.map((a, i) => (
              <div key={i}>
                <Icon name="trend" />
                <div>
                  <strong>
                    {date(a.date)} · {fmt(a.excluded_qty)} {item.stock_unit}
                  </strong>
                  <p>
                    {a.reason === 'LARGE_CUSTOMER_DAY'
                      ? 'Крупная сумма покупок одного клиента за день'
                      : 'Разовая крупная накладная'}
                  </p>
                  <details>
                    <summary>
                      {a.document_ids.length} документов{a.customer_id ? ` · ${a.customer_id}` : ''}
                    </summary>
                    <p className="document-list">{a.document_ids.join(', ')}</p>
                  </details>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}
      {!!item.warnings.length && (
        <section>
          <h3>Что проверить</h3>
          <ul className="issue-list">
            {item.warnings.map((w, i) => (
              <li key={i}>
                <Icon name="alert" size={17} />
                <div>
                  <strong>{issueMessage(w)}</strong>
                  <small>{w.code}</small>
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}
      {!!item.assumptions.length && (
        <section>
          <h3>Принятые допущения</h3>
          {item.assumptions.map((a, i) => (
            <p key={i} className="notice amber">
              {a.reason} · {a.accepted_by}
            </p>
          ))}
        </section>
      )}
      {!blocked && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            edit(Number(quantity), reason);
          }}
        >
          <fieldset disabled={busy} className="override-form">
            <h3>Скорректировать заказ</h3>
            <p className="muted small">
              Правка сохранит рекомендацию и отменит прежнее утверждение.
            </p>
            <div className="form-grid">
              <label>
                Количество, {item.purchase_unit}
                <input
                  type="number"
                  min="0"
                  step="any"
                  required
                  value={quantity}
                  onChange={(e) => setQuantity(e.target.value)}
                />
              </label>
              <label>
                Причина изменения
                <input
                  required
                  value={reason}
                  placeholder="Например, согласована акция"
                  onChange={(e) => setReason(e.target.value)}
                />
              </label>
            </div>
            {item.override && (
              <p className="muted small">Предыдущая правка: {item.override.reason}</p>
            )}
            <div className="actions">
              <button className="primary" type="submit">
                Сохранить корректировку
              </button>
              {item.override && (
                <button className="secondary" type="button" onClick={() => edit(null, '')}>
                  Вернуть рекомендацию
                </button>
              )}
            </div>
          </fieldset>
        </form>
      )}
      {!!item.source_refs.length && (
        <details className="advanced">
          <summary>Источники позиции</summary>
          {item.source_refs.map((r, i) => (
            <p className="muted small" key={i}>
              {r.source_id}
              {r.sheet && ` · ${r.sheet}`}
              {r.cell_range && ` · ${r.cell_range}`}
            </p>
          ))}
        </details>
      )}
    </div>
  );
}
