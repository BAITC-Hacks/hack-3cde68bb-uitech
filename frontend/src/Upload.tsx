import { useState } from 'react';
import type { Dataset, Fixture } from './types';
import { Icon } from './ui';

const roles = [
  ['sales_transactions', 'Динамика продаж', 'Динамика'],
  ['sales_monthly', 'Продажи по месяцам', 'Ежемесячные продажи'],
  ['stock_monthly', 'Остатки по месяцам', 'Ежемесячные остатки'],
  ['inventory_transit', 'Остаток и товары в пути', 'Товар в пути'],
  ['moq', 'Минимум / кратность закупки', 'MOQ'],
  ['seasonality', 'Сезонность', 'Сезонность'],
];
export default function Upload({
  busy,
  upload,
  normalized,
}: {
  busy: boolean;
  upload: (f: FormData) => void;
  normalized: (d: Dataset, params?: Fixture['calculation']) => void;
}) {
  const [files, setFiles] = useState<{ file: File; role: string }[]>([]);
  const [error, setError] = useState('');
  const [name, setName] = useState('Systeme Electric');
  const [supplier, setSupplier] = useState('SYSTEME');
  async function json(file: File | undefined) {
    if (!file) return;
    setError('');
    try {
      if (file.size > 150 * 1024 * 1024) throw new Error('Максимальный размер JSON — 150 МБ.');
      const value = JSON.parse(await file.text());
      const d = value.dataset || value;
      if (!d.schema_version || !Array.isArray(d.products))
        throw new Error('Нужен нормализованный набор или контрольный пример.');
      normalized(d, value.calculation);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось прочитать JSON.');
    }
  }
  return (
    <div className="modal-body">
      <fieldset disabled={busy}>
        <p className="muted">
          Выберите поставщика и шесть XLSX из распакованного архива. Исходные файлы не изменяются.
        </p>
        <label>
          Поставщик файлов
          <select
            value={supplier}
            onChange={(e) => {
              setSupplier(e.target.value);
              if (name === 'Systeme Electric' || name === 'IEK')
                setName(e.target.value === 'IEK' ? 'IEK' : 'Systeme Electric');
            }}
          >
            <option value="SYSTEME">Systeme Electric</option>
            <option value="IEK">IEK</option>
          </select>
        </label>
        {supplier === 'IEK' && (
          <p className="notice amber">
            В архиве IEK нет актуального остатка. Количества партий и сроки «поступление до»
            сохранятся для уточнения единиц; в расчёт заказа они пока не входят.
          </p>
        )}
        <label className="dropzone">
          <span className="upload-symbol">
            <Icon name="upload" size={26} />
          </span>
          <strong>Выбрать Excel-файлы</strong>
          <span>6 файлов · до 15 МБ каждый</span>
          <input
            type="file"
            accept=".xlsx"
            multiple
            aria-label="Выбрать Excel-файлы"
            onChange={(e) => {
              setError('');
              setFiles(
                Array.from(e.target.files || []).map((file) => ({
                  file,
                  role:
                    roles.find((r) => file.name.startsWith(r[2]))?.[0] ||
                    (file.name.startsWith('Путь') ? 'inventory_transit' : ''),
                })),
              );
            }}
          />
        </label>
        {!!files.length && (
          <form
            onSubmit={(e) => {
              e.preventDefault();
              setError('');
              if (files.length !== 6 || new Set(files.map((f) => f.role)).size !== 6) {
                setError('Нужно ровно шесть файлов с разными ролями.');
                return;
              }
              if (
                files.some((f) => f.file.size > 15 * 1024 * 1024) ||
                files.reduce((sum, f) => sum + f.file.size, 0) > 39 * 1024 * 1024
              ) {
                setError('Файлы превышают допустимый размер: 15 МБ каждый, до 39 МБ суммарно.');
                return;
              }
              const form = new FormData();
              form.append(
                'manifest',
                JSON.stringify({
                  name,
                  data_kind: 'real',
                  timezone: 'Asia/Almaty',
                  files: files.map((f) => ({
                    part_name: f.role,
                    supplier_id: supplier,
                    role: f.role,
                  })),
                }),
              );
              files.forEach((f) => form.append(f.role, f.file));
              upload(form);
            }}
          >
            <label>
              Название набора
              <input required value={name} onChange={(e) => setName(e.target.value)} />
            </label>
            <div className="upload-files">
              {files.map((f, i) => (
                <div className="upload-row" key={i}>
                  <Icon name="file" />
                  <span title={f.file.name}>{f.file.name}</span>
                  <select
                    aria-label={`Роль файла ${f.file.name}`}
                    required
                    value={f.role}
                    onChange={(e) =>
                      setFiles(
                        files.map((value, j) =>
                          i === j ? { ...value, role: e.target.value } : value,
                        ),
                      )
                    }
                  >
                    <option value="">Выберите роль</option>
                    {roles.map(([key, label]) => (
                      <option key={key} value={key}>
                        {label}
                      </option>
                    ))}
                  </select>
                </div>
              ))}
            </div>
            <button className="primary full" type="submit">
              Импортировать {files.length} файлов
              <Icon name="arrow" />
            </button>
          </form>
        )}
        {error && (
          <p role="alert" className="notice red">
            {error}
          </p>
        )}
        <details className="advanced">
          <summary>Загрузить нормализованный JSON или контрольный пример</summary>
          <p className="muted small">
            Для подготовленных наборов и данных, уточнённых аналитиком. Сохраняется новый набор;
            старый остаётся в истории.
          </p>
          <input
            aria-label="JSON-файл"
            type="file"
            accept=".json"
            onChange={(e) => void json(e.target.files?.[0])}
          />
        </details>
      </fieldset>
    </div>
  );
}
