import { useState } from 'react';
import type { Dataset, Params, ProductCorrection } from './types';
import { fmt } from './ui';

export default function SourceEditor({
  dataset,
  params,
  initialProductId,
  busy,
  submit,
}: {
  dataset: Dataset;
  params: Params;
  initialProductId?: string;
  busy: boolean;
  submit: (productId: string, correction: ProductCorrection) => void;
}) {
  const [productId, setProductId] = useState(initialProductId || dataset.products[0].product_id);
  const [query, setQuery] = useState('');
  const product = dataset.products.find((p) => p.product_id === productId)!;
  const products = dataset.products.filter(
    (p) =>
      p.product_id === productId ||
      `${p.name} ${p.sku_1c} ${p.supplier_article || ''}`
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  return (
    <div className="modal-body settings">
      <p className="muted">
        Укажите проверенные значения. Будет сохранён новый набор; исходный импорт и прежние расчёты
        останутся доступны.
      </p>
      <div className="form-grid">
        <label>
          Поиск товара
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Название, код 1С или артикул"
            disabled={busy}
          />
        </label>
        <label>
          Товар для уточнения
          <select value={productId} disabled={busy} onChange={(e) => setProductId(e.target.value)}>
            {products.map((p) => (
              <option key={p.product_id} value={p.product_id}>
                {p.sku_1c} · {p.name}
              </option>
            ))}
          </select>
        </label>
      </div>
      <ProductForm
        key={productId}
        dataset={dataset}
        params={params}
        productId={product.product_id}
        busy={busy}
        submit={(correction) => submit(productId, correction)}
      />
    </div>
  );
}

function ProductForm({
  dataset,
  params,
  productId,
  busy,
  submit,
}: {
  dataset: Dataset;
  params: Params;
  productId: string;
  busy: boolean;
  submit: (correction: ProductCorrection) => void;
}) {
  const p = dataset.products.find((p) => p.product_id === productId)!;
  const initialStock = dataset.inventory.find(
    (i) =>
      i.product_id === productId &&
      i.warehouse_id === params.warehouse_id &&
      i.as_of === params.as_of,
  );
  const policy = params.supplier_policies.find((s) => s.supplier_id === p.supplier_id);
  const [editStock, setEditStock] = useState(true);
  const [editPurchase, setEditPurchase] = useState(false);
  const [editPolicy, setEditPolicy] = useState(false);
  const [warehouse, setWarehouse] = useState(params.warehouse_id);
  const [asOf, setAsOf] = useState(params.as_of);
  const [stock, setStock] = useState(
    initialStock?.free_stock_qty == null ? '' : String(initialStock.free_stock_qty),
  );
  const [unit, setUnit] = useState(p.purchase_unit);
  const [factor, setFactor] = useState(
    p.purchase_unit_factor == null ? '' : String(p.purchase_unit_factor),
  );
  const [category, setCategory] = useState(p.category_id || '');
  const [moq, setMoq] = useState(p.moq_purchase_qty == null ? '' : String(p.moq_purchase_qty));
  const [multiple, setMultiple] = useState(
    p.pack_multiple_purchase_qty == null ? '' : String(p.pack_multiple_purchase_qty),
  );
  const [lead, setLead] = useState(
    policy?.lead_time_days == null ? '' : String(policy.lead_time_days),
  );
  const [review, setReview] = useState(
    policy?.review_period_days == null ? '' : String(policy.review_period_days),
  );
  const [author, setAuthor] = useState('');
  const [reason, setReason] = useState('');
  const nullable = (value: string) => (value.trim() === '' ? null : Number(value));
  const warehouses = [
    ...new Set([...dataset.inventory, ...dataset.sales_coverage].map((i) => i.warehouse_id)),
  ];
  const history = dataset.sources
    .flatMap((s) => (s.correction?.product_id === productId ? [s.correction] : []))
    .reverse();
  return (
    <>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          submit({
            author: author.trim(),
            reason: reason.trim(),
            stock: editStock
              ? { warehouse_id: warehouse, as_of: asOf, free_stock_qty: Number(stock) }
              : null,
            purchase: editPurchase
              ? {
                  purchase_unit: unit.trim(),
                  purchase_unit_factor: nullable(factor),
                  category_id: category.trim() || null,
                  moq_purchase_qty: nullable(moq),
                  pack_multiple_purchase_qty: nullable(multiple),
                }
              : null,
            supplier_policy: editPolicy
              ? {
                  supplier_id: p.supplier_id,
                  lead_time_days: Number(lead),
                  review_period_days: Number(review),
                }
              : null,
          });
        }}
      >
        <fieldset disabled={busy}>
          <label className="source-editor-toggle">
            <input
              type="checkbox"
              checked={editStock}
              onChange={(e) => setEditStock(e.target.checked)}
            />
            Уточнить свободный остаток
          </label>
          {editStock && (
            <div className="form-grid three">
              <label>
                Склад остатка
                <select
                  required
                  value={warehouse}
                  onChange={(e) => {
                    setWarehouse(e.target.value);
                    setStock('');
                  }}
                >
                  <option value="" disabled>
                    Выберите склад
                  </option>
                  {warehouses.map((w) => (
                    <option key={w}>{w}</option>
                  ))}
                </select>
              </label>
              <label>
                Дата актуальности остатка
                <input
                  type="date"
                  required
                  value={asOf}
                  onChange={(e) => {
                    setAsOf(e.target.value);
                    setStock('');
                  }}
                />
              </label>
              <label>
                Свободный остаток, {p.stock_unit}
                <input
                  type="number"
                  required
                  min="0"
                  step="any"
                  max="9007199254740991"
                  value={stock}
                  onChange={(e) => setStock(e.target.value)}
                  placeholder="Неизвестен"
                />
              </label>
            </div>
          )}
          <label className="source-editor-toggle">
            <input
              type="checkbox"
              checked={editPurchase}
              onChange={(e) => setEditPurchase(e.target.checked)}
            />
            Уточнить правила закупки
          </label>
          {editPurchase && (
            <>
              <p className="muted small">
                Пустое числовое поле означает «неизвестно». MOQ 0 допустим, коэффициент и кратность
                должны быть больше нуля. Единица хранения остаётся {p.stock_unit}.
              </p>
              <div className="form-grid">
                <label>
                  Единица закупки
                  <input required value={unit} onChange={(e) => setUnit(e.target.value)} />
                </label>
                <label>
                  Единиц хранения в закупке
                  <input
                    type="number"
                    min="0.000000001"
                    step="any"
                    value={factor}
                    onChange={(e) => setFactor(e.target.value)}
                  />
                </label>
                <label>
                  MOQ в единицах закупки
                  <input
                    type="number"
                    min="0"
                    step="any"
                    value={moq}
                    onChange={(e) => setMoq(e.target.value)}
                  />
                </label>
                <label>
                  Кратность закупки
                  <input
                    type="number"
                    min="0.000000001"
                    step="any"
                    value={multiple}
                    onChange={(e) => setMultiple(e.target.value)}
                  />
                </label>
                <label>
                  Категория
                  <input
                    value={category}
                    onChange={(e) => setCategory(e.target.value)}
                    placeholder="Неизвестна"
                  />
                </label>
              </div>
            </>
          )}
          <label className="source-editor-toggle">
            <input
              type="checkbox"
              checked={editPolicy}
              onChange={(e) => setEditPolicy(e.target.checked)}
            />
            Уточнить сроки поставщика
          </label>
          {editPolicy && (
            <>
              <p className="muted small">
                Применяется ко всем товарам поставщика{' '}
                {dataset.suppliers.find((s) => s.supplier_id === p.supplier_id)?.name ||
                  p.supplier_id}{' '}
                в новом расчёте.
              </p>
              <div className="form-grid">
                <label>
                  Срок поставки, дней
                  <input
                    required
                    type="number"
                    min="0"
                    max="365"
                    step="1"
                    value={lead}
                    onChange={(e) => setLead(e.target.value)}
                  />
                </label>
                <label>
                  Период между заказами, дней
                  <input
                    required
                    type="number"
                    min="1"
                    max="366"
                    step="1"
                    value={review}
                    onChange={(e) => setReview(e.target.value)}
                  />
                </label>
              </div>
            </>
          )}
          <div className="form-grid">
            <label>
              Автор исправления
              <input
                required
                maxLength={200}
                value={author}
                onChange={(e) => setAuthor(e.target.value)}
              />
            </label>
            <label>
              Причина и источник проверки
              <textarea
                required
                maxLength={2000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                placeholder="Например: сверено с отчётом склада на указанную дату"
              />
            </label>
          </div>
          <p className="muted small">
            После сохранения проверьте параметры и выполните новый расчёт. Замечания о транзите,
            истории и наличии сохранятся, пока соответствующие сведения не подтверждены.
          </p>
          <button
            className="primary full"
            type="submit"
            disabled={!editStock && !editPurchase && !editPolicy}
          >
            Сохранить и перейти к расчёту
          </button>
        </fieldset>
      </form>
      {!!history.length && (
        <section className="correction-history">
          <h3>История исправлений товара</h3>
          {history.map((a) => (
            <article key={a.created_at} className="notice">
              <strong>
                {a.author} · {new Date(a.created_at).toLocaleString('ru-RU')}
              </strong>
              <p>{a.reason}</p>
              {a.after_stock && (
                <p>
                  Остаток: {fmt(a.before_stock?.free_stock_qty)} →{' '}
                  {fmt(a.after_stock.free_stock_qty)} {p.stock_unit} · {a.after_stock.warehouse_id}{' '}
                  · {a.after_stock.as_of}
                </p>
              )}
              <p>
                Единица закупки: {a.before_product.purchase_unit} → {a.after_product.purchase_unit};
                коэффициент: {fmt(a.before_product.purchase_unit_factor)} →{' '}
                {fmt(a.after_product.purchase_unit_factor)}; MOQ:{' '}
                {fmt(a.before_product.moq_purchase_qty)} → {fmt(a.after_product.moq_purchase_qty)};
                кратность: {fmt(a.before_product.pack_multiple_purchase_qty)} →{' '}
                {fmt(a.after_product.pack_multiple_purchase_qty)}.
              </p>
              <p>
                Категория: {a.before_product.category_id || 'неизвестна'} →{' '}
                {a.after_product.category_id || 'неизвестна'}.
              </p>
              {a.supplier_policy && (
                <p>
                  Срок поставки: {a.supplier_policy.lead_time_days} дн.; период заказа:{' '}
                  {a.supplier_policy.review_period_days} дн.
                </p>
              )}
            </article>
          ))}
        </section>
      )}
    </>
  );
}
