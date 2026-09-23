import { useEffect, useMemo, useRef, useState } from 'react';
import { api, ApiError, downloadCsv } from './api';
import type { Calculation, Dataset, Fixture, Item, Params, Summary } from './types';
import { Badge, date, fmt, Icon, Kind, Modal } from './ui';
import Settings, { initialParams } from './Settings';
import Upload from './Upload';
import ItemDetail from './ItemDetail';

const fixtures = import.meta.glob<Fixture>('../../fixtures/contract/*.json', { import: 'default' });
const demos = [
  ['15_supplier_groups', 'Заказы двум поставщикам', 'Группировка, объяснение и экспорт заказов.'],
  [
    '08_one_off_order',
    'Разовая крупная продажа',
    'Всплеск на 1 000 шт. не завышает регулярный спрос.',
  ],
  [
    '09_split_customer_order',
    'Один клиент, много накладных',
    'Разовая покупка разделена на 100 документов.',
  ],
  ['07_confirmed_stockout', 'Упущенный спрос', 'Восстановление потребности за дни без товара.'],
  ['06_seasonal_peak', 'Сезонный пик', 'Рост потребности в месяц высокого спроса.'],
  ['10_sustained_growth', 'Устойчивый рост', 'Многомесячный тренд сохраняется в прогнозе.'],
  ['14_missing_stock', 'Неизвестный остаток', 'Неполные данные блокируют утверждение.'],
];
type View = 'orders' | 'data';
type Dialog = 'upload' | 'demo' | 'settings' | 'approve' | 'export' | 'quality' | null;
const readSaved = () => {
  try {
    return localStorage.getItem('uitech.lastCalculation');
  } catch {
    return null;
  }
};
function saveId(id: string | null) {
  try {
    if (id) localStorage.setItem('uitech.lastCalculation', id);
    else localStorage.removeItem('uitech.lastCalculation');
  } catch {
    /* Storage is optional. */
  }
}

export default function App() {
  const [catalog, setCatalog] = useState<Summary[]>([]);
  const [dataset, setDataset] = useState<Dataset | null>(null);
  const [datasetId, setDatasetId] = useState('');
  const [params, setParams] = useState<Params | null>(null);
  const [calc, setCalc] = useState<Calculation | null>(null);
  const [view, setView] = useState<View>('orders');
  const [dialog, setDialog] = useState<Dialog>(null);
  const [itemId, setItemId] = useState<string | null>(null);
  const [busy, setBusy] = useState('');
  const lock = useRef(false);
  const [error, setError] = useState('');
  const [success, setSuccess] = useState('');
  const [online, setOnline] = useState(false);
  const [search, setSearch] = useState('');
  const [supplier, setSupplier] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [reviewer, setReviewer] = useState('');
  const [ack, setAck] = useState<string[]>([]);
  const [exportSupplier, setExportSupplier] = useState('');
  const summary = catalog.find((d) => d.dataset_id === datasetId);
  const selected = calc?.items.find((item) => item.item_id === itemId);
  const supplierName = (id: string) =>
    dataset?.suppliers.find((s) => s.supplier_id === id)?.name || id;
  const filtered = useMemo(
    () =>
      (calc?.items || []).filter(
        (item) =>
          (!supplier || item.supplier_id === supplier) &&
          (!status || item.status === status) &&
          `${item.name} ${item.sku_1c} ${item.supplier_article || ''}`
            .toLocaleLowerCase()
            .includes(search.toLocaleLowerCase()),
      ),
    [calc, supplier, status, search],
  );
  const pageCount = Math.max(1, Math.ceil(filtered.length / 20));
  const currentPage = Math.min(page, pageCount - 1);
  const warningMap = new Map<string, string>();
  calc?.items.forEach((item) => {
    item.warnings.forEach((w) => warningMap.set(w.code, w.message));
    item.assumptions.forEach((a) => warningMap.set(a.code, a.reason));
  });
  const warningCodes = [...warningMap.keys()];
  const issueGroups = useMemo(() => {
    const groups = new Map<string, { message: string; count: number; severity?: string }>();
    dataset?.issues.forEach((issue) => {
      const old = groups.get(issue.code);
      groups.set(issue.code, {
        message: old?.message || issue.message,
        count: (old?.count || 0) + 1,
        severity: issue.severity,
      });
    });
    return [...groups.entries()].sort((a, b) => b[1].count - a[1].count);
  }, [dataset]);

  async function perform(label: string, action: () => Promise<void>) {
    if (lock.current) return;
    lock.current = true;
    setBusy(label);
    setError('');
    setSuccess('');
    try {
      await action();
      setOnline(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Не удалось выполнить действие.');
      if (e instanceof ApiError && (e.status === 0 || e.status >= 500)) setOnline(false);
      if (e instanceof ApiError && e.status === 409 && calc) {
        try {
          const latest = await api<Calculation>(`/calculations/${calc.calculation_id}`);
          setCalc(latest);
          setAck([]);
          setError(
            'Версия расчёта изменилась. Загружена актуальная версия; проверьте её перед повторным действием.',
          );
        } catch {
          /* Original error remains visible. */
        }
      }
    } finally {
      lock.current = false;
      setBusy('');
    }
  }
  function show(which: Dialog) {
    setError('');
    setDialog(which);
  }
  function remember(result: Calculation) {
    setCalc(result);
    saveId(result.calculation_id);
    setPage(0);
  }
  async function loadDataset(id: string, config?: Params, result?: Calculation) {
    const data = await api<Dataset>(`/datasets/${encodeURIComponent(id)}/data`);
    setDataset(data);
    setDatasetId(id);
    setParams(config ? { ...config, dataset_id: id } : initialParams(data, id));
    setSearch('');
    setSupplier('');
    setStatus('');
    setPage(0);
    setItemId(null);
    setView('orders');
    if (result) remember(result);
    else {
      setCalc(null);
      saveId(null);
    }
  }
  async function refreshCatalog() {
    const list = await api<Summary[]>('/datasets');
    setCatalog(list);
    return list;
  }
  useEffect(() => {
    void perform('Открываем рабочее место…', async () => {
      await api('/health');
      const list = await refreshCatalog();
      const saved = readSaved();
      if (saved) {
        try {
          const result = await api<Calculation>(`/calculations/${encodeURIComponent(saved)}`);
          await loadDataset(result.dataset_id, result.methodology.parameters, result);
          return;
        } catch (e) {
          if (!(e instanceof ApiError) || e.status !== 404) throw e;
          saveId(null);
        }
      }
      const first = list.find((d) => d.data_kind === 'real') || list[0];
      if (first) await loadDataset(first.dataset_id);
    });
  }, []);
  useEffect(() => {
    if (!success) return;
    const timeout = setTimeout(() => setSuccess(''), 7000);
    return () => clearTimeout(timeout);
  }, [success]);
  async function demo(name: string) {
    await perform('Готовим демонстрационный расчёт…', async () => {
      const fixture = await fixtures[`../../fixtures/contract/${name}.json`]();
      const demoDataset = {
        ...fixture.dataset,
        name: `Демо: ${demos.find((d) => d[0] === name)?.[1] || name}`,
      };
      const imported = await api<Summary>('/datasets', demoDataset);
      const config = { ...fixture.calculation, dataset_id: imported.dataset_id };
      const result = await api<Calculation>('/calculations', config);
      await refreshCatalog();
      await loadDataset(imported.dataset_id, config, result);
      setDialog(null);
      setSuccess('Демонстрационный расчёт готов. Откройте товар для объяснения.');
    });
  }
  function calculate(value: Params) {
    void perform('Рассчитываем потребность…', async () => {
      const result = await api<Calculation>('/calculations', value);
      setParams(value);
      remember(result);
      setView('orders');
      setItemId(null);
      setDialog(null);
      setSuccess('Расчёт готов. Проверьте рекомендации перед утверждением.');
    });
  }
  const positive = calc?.items.filter((i) => (i.final_purchase_qty || 0) > 0).length || 0;
  const shortage = calc?.items.filter((i) => !!i.first_stockout_date).length || 0;
  const blocked = calc?.summary.needs_input_count || 0;
  const approvingDisabled =
    !calc || !calc.items.length || blocked > 0 || calc.status === 'APPROVED' || !!busy;
  const close = () => {
    if (!busy) {
      setDialog(null);
      setItemId(null);
      setError('');
    }
  };
  const modalFeedback = (
    <>
      {error && (
        <p className="notice red modal-feedback" role="alert">
          {error}
        </p>
      )}
      {busy && (
        <p className="modal-feedback muted" role="status">
          <span className="spinner" />
          {busy}
        </p>
      )}
    </>
  );

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          className="brand"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            setView('orders');
          }}
        >
          <span className="brand-mark">
            <Icon name="boxes" size={25} />
          </span>
          uitech<span className="brand-point">.</span>
        </a>
        <p className="sidebar-caption">УПРАВЛЕНИЕ ЗАПАСАМИ</p>
        <nav aria-label="Основная навигация">
          <button
            disabled={!!busy}
            className={view === 'orders' ? 'nav-item active' : 'nav-item'}
            onClick={() => setView('orders')}
          >
            <Icon name="grid" />
            Пополнение склада
          </button>
          <button
            disabled={!!busy}
            className={view === 'data' ? 'nav-item active' : 'nav-item'}
            onClick={() => setView('data')}
          >
            <Icon name="database" />
            Источники данных<span className="nav-count">{catalog.length}</span>
          </button>
          <button
            className="nav-item"
            disabled={!dataset || !!busy}
            onClick={() => show('settings')}
          >
            <Icon name="sliders" />
            Параметры расчёта
          </button>
        </nav>
        <div className="sidebar-note">
          <span className="leaf-mark">
            <Icon name="trend" size={23} />
          </span>
          <strong>Закупки с объяснением</strong>
          <p>От истории продаж до обоснованного заказа.</p>
          <button onClick={() => show('demo')} disabled={!!busy}>
            Проверить на демо
            <Icon name="arrow" size={16} />
          </button>
        </div>
        <div className="workspace">
          <span className="avatar">U</span>
          <div>
            <strong>Команда uitech</strong>
            <small>HackAlem · Логистика</small>
          </div>
          <span className="workspace-dot" />
        </div>
      </aside>
      <div className="workspace-main">
        <header className="topbar">
          <div>
            <span className="muted">Рабочее пространство</span>
            <span className="crumb-separator">/</span>
            <strong>{view === 'orders' ? 'Закупки' : 'Данные'}</strong>
          </div>
          <span className={`connection ${online ? '' : 'offline'}`}>
            <span className="dot" />
            {online ? 'Локальный сервер подключён' : busy ? 'Подключение…' : 'Нет подключения'}
          </span>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <p className="eyebrow">ПЛАНИРОВАНИЕ ЗАКУПОК</p>
              <h1>{view === 'orders' ? 'Пополнение склада' : 'Источники данных'}</h1>
              <p className="muted">
                {view === 'orders'
                  ? 'Регулярный спрос. Достаточный запас. Обоснованный заказ.'
                  : 'Загрузите данные поставщика и проверьте их перед расчётом.'}
              </p>
            </div>
            <div className="actions">
              <button className="secondary" disabled={!!busy} onClick={() => show('demo')}>
                <Icon name="play" size={16} />
                Демо-сценарии
              </button>
              <button className="primary" disabled={!!busy} onClick={() => show('upload')}>
                <Icon name="upload" size={17} />
                Загрузить данные
              </button>
            </div>
          </div>
          <div aria-live="polite">
            {error && !dialog && !selected && (
              <div className="notice red">
                <Icon name="alert" />
                {error}
                {!online && (
                  <button
                    className="text-button"
                    disabled={!!busy}
                    onClick={() =>
                      void perform('Переподключаемся…', async () => {
                        const list = await refreshCatalog();
                        if (!dataset && list.length) await loadDataset(list[0].dataset_id);
                      })
                    }
                  >
                    Повторить
                  </button>
                )}
              </div>
            )}
            {success && (
              <div className="notice green">
                <Icon name="check" />
                {success}
              </div>
            )}
            {busy && !dialog && !selected && (
              <div className="loading-line" role="status">
                <span className="spinner" />
                {busy}
              </div>
            )}
          </div>
          {view === 'data' ? (
            <>
              <div className="section-title">
                <h2>
                  Сохранённые наборы <span className="count">{catalog.length}</span>
                </h2>
                <button
                  className="text-button"
                  disabled={!!busy}
                  onClick={() =>
                    void perform('Обновляем список…', async () => {
                      await refreshCatalog();
                    })
                  }
                >
                  <Icon name="refresh" size={16} />
                  Обновить
                </button>
              </div>
              <div className="dataset-grid">
                {catalog.map((d) => (
                  <article className="dataset-card" key={d.dataset_id}>
                    <div className="section-title">
                      <span className="source-icon">
                        <Icon name="database" />
                      </span>
                      <Kind kind={d.data_kind} />
                    </div>
                    <h3>{d.name}</h3>
                    <p className="muted small">Загружено {date(d.created_at)}</p>
                    <div className="dataset-numbers">
                      <span>
                        <strong>{fmt(d.counts.products)}</strong>товаров
                      </span>
                      <span>
                        <strong>{fmt(d.counts.sales)}</strong>строк продаж
                      </span>
                    </div>
                    <div className="section-title">
                      <Badge status={d.status} />
                      <button
                        disabled={!!busy}
                        className="text-button"
                        onClick={() =>
                          void perform('Открываем набор…', async () => {
                            await loadDataset(d.dataset_id);
                          })
                        }
                      >
                        Открыть
                        <Icon name="arrow" size={16} />
                      </button>
                    </div>
                  </article>
                ))}
              </div>
              {!catalog.length && <Empty openDemo={() => show('demo')} />}
            </>
          ) : (
            <>
              {dataset && (
                <div className="dataset-bar">
                  <div className="dataset-select">
                    <Icon name="database" />
                    <label className="sr-only" htmlFor="dataset">
                      Набор данных
                    </label>
                    <select
                      id="dataset"
                      value={datasetId}
                      disabled={!!busy}
                      onChange={(e) =>
                        void perform('Открываем набор…', async () => {
                          await loadDataset(e.target.value);
                        })
                      }
                    >
                      {catalog.map((d) => (
                        <option value={d.dataset_id} key={d.dataset_id}>
                          {d.name}
                        </option>
                      ))}
                    </select>
                    <Kind kind={dataset.data_kind} />
                  </div>
                  <button className="text-button" disabled={!!busy} onClick={() => show('quality')}>
                    Качество данных
                    <Icon name="arrow" size={15} />
                  </button>
                </div>
              )}
              {dataset && !calc && (
                <div className="start-panel">
                  <div className="start-symbol">
                    <Icon name="boxes" size={35} />
                  </div>
                  <div>
                    <h2>Набор готов к проверке</h2>
                    <p>
                      {fmt(dataset.products.length)} товаров · {fmt(summary?.counts.sales)} строк
                      продаж. Задайте сроки поставки и правила запаса, чтобы получить рекомендации.
                    </p>
                    {dataset.data_kind === 'real' && (
                      <small>
                        Неполные позиции будут отмечены. Данные не дополняются предположениями
                        автоматически.
                      </small>
                    )}
                  </div>
                  <button className="primary" disabled={!!busy} onClick={() => show('settings')}>
                    Настроить расчёт
                    <Icon name="arrow" size={17} />
                  </button>
                </div>
              )}
              {!dataset && !busy && <Empty openDemo={() => show('demo')} />}
              {dataset && (
                <div className="metrics">
                  {[
                    {
                      label: 'ПОЗИЦИЙ К ЗАКАЗУ',
                      value: calc ? positive : null,
                      icon: 'boxes',
                      note: calc
                        ? `из ${fmt(calc.items.length)} товаров в расчёте`
                        : `${fmt(dataset.products.length)} товаров в наборе`,
                      color: 'green',
                    },
                    {
                      label: 'РИСК ДЕФИЦИТА',
                      value: calc ? shortage : null,
                      icon: 'clock',
                      note: 'до конца горизонта прогноза',
                      color: 'orange',
                    },
                    {
                      label: 'НУЖНЫ ДАННЫЕ',
                      value: calc ? blocked : null,
                      icon: 'alert',
                      note: calc ? 'позиций требуют уточнения' : 'определится после расчёта',
                      color: 'yellow',
                    },
                    {
                      label: 'ПОСТАВЩИКОВ',
                      value: calc ? calc.summary.supplier_count : dataset.suppliers.length,
                      icon: 'database',
                      note: 'отдельный заказ каждому',
                      color: 'blue',
                    },
                  ].map((card) => (
                    <article className="metric" key={card.label}>
                      <div>
                        <span>{card.label}</span>
                        <i className={card.color}>
                          <Icon name={card.icon} />
                        </i>
                      </div>
                      <strong>{fmt(card.value)}</strong>
                      <p>{card.note}</p>
                    </article>
                  ))}
                </div>
              )}
              {calc && (
                <section className="recommendations panel">
                  <div className="panel-heading">
                    <div>
                      <div className="heading-with-badge">
                        <h2>Рекомендации к закупке</h2>
                        <Badge status={calc.status} />
                      </div>
                      <p className="muted small">
                        На {date(calc.as_of)} · склад {calc.warehouse_id} · версия {calc.revision}
                      </p>
                    </div>
                    <button
                      className="secondary"
                      disabled={!!busy}
                      onClick={() => show('settings')}
                    >
                      <Icon name="sliders" size={17} />
                      Пересчитать
                    </button>
                  </div>
                  {blocked > 0 && (
                    <div className="inline-warning">
                      <Icon name="alert" size={18} />
                      <span>
                        {fmt(blocked)} позиций требуют уточнения. Откройте товар, чтобы увидеть
                        причины. Утверждение недоступно.
                      </span>
                    </div>
                  )}
                  <div className="table-toolbar">
                    <label className="search-box">
                      <Icon name="search" size={18} />
                      <input
                        placeholder="Найти товар, артикул или код…"
                        aria-label="Поиск товара"
                        value={search}
                        onChange={(e) => {
                          setSearch(e.target.value);
                          setPage(0);
                        }}
                      />
                    </label>
                    <select
                      aria-label="Фильтр поставщика"
                      value={supplier}
                      onChange={(e) => {
                        setSupplier(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">Все поставщики</option>
                      {calc.supplier_groups.map((g) => (
                        <option key={g.supplier_id} value={g.supplier_id}>
                          {supplierName(g.supplier_id)}
                        </option>
                      ))}
                    </select>
                    <select
                      aria-label="Фильтр статуса"
                      value={status}
                      onChange={(e) => {
                        setStatus(e.target.value);
                        setPage(0);
                      }}
                    >
                      <option value="">Все статусы</option>
                      <option value="READY">Готово к заказу</option>
                      <option value="NEEDS_INPUT">Нужны данные</option>
                      <option value="ASSUMED">С допущениями</option>
                      <option value="EXCLUDED_BY_POLICY">Закупка запрещена</option>
                    </select>
                  </div>
                  <div className="table-scroll">
                    <table>
                      <thead>
                        <tr>
                          <th>Товар / поставщик</th>
                          <th>Статус</th>
                          <th className="number">Остаток</th>
                          <th className="number">В пути</th>
                          <th>Риск дефицита</th>
                          <th className="number">К заказу</th>
                          <th />
                        </tr>
                      </thead>
                      <tbody>
                        {filtered.slice(currentPage * 20, currentPage * 20 + 20).map((item) => (
                          <tr key={item.item_id}>
                            <td>
                              <button
                                className="product-link"
                                disabled={!!busy}
                                onClick={() => {
                                  setError('');
                                  setItemId(item.item_id);
                                }}
                              >
                                {item.name}
                              </button>
                              <span className="product-meta">
                                {item.supplier_article || item.sku_1c} <b>·</b>{' '}
                                {supplierName(item.supplier_id)}
                              </span>
                            </td>
                            <td>
                              <Badge status={item.status} />
                            </td>
                            <td className="number">
                              {fmt(item.factors.free_stock_qty)}
                              <small>{item.stock_unit}</small>
                            </td>
                            <td className="number">
                              {fmt(item.factors.eligible_inbound_qty)}
                              <small>{item.stock_unit}</small>
                            </td>
                            <td>
                              <span className={item.first_stockout_date ? 'risk-date' : 'muted'}>
                                {item.first_stockout_date
                                  ? date(item.first_stockout_date)
                                  : item.status === 'NEEDS_INPUT'
                                    ? 'Нет расчёта'
                                    : 'Не ожидается'}
                              </span>
                            </td>
                            <td className="number order-quantity">
                              {fmt(item.final_purchase_qty)}
                              <small>
                                {item.purchase_unit}
                                {item.override ? ' · вручную' : ''}
                              </small>
                            </td>
                            <td>
                              <button
                                className="icon-button"
                                disabled={!!busy}
                                aria-label={`Открыть ${item.name}`}
                                onClick={() => {
                                  setError('');
                                  setItemId(item.item_id);
                                }}
                              >
                                <Icon name="arrow" size={18} />
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                    {!filtered.length && (
                      <p className="table-empty">По выбранным фильтрам товаров нет.</p>
                    )}
                  </div>
                  <div className="table-footer">
                    <span>
                      Показано {filtered.length ? currentPage * 20 + 1 : 0}–
                      {Math.min((currentPage + 1) * 20, filtered.length)} из {fmt(filtered.length)}
                    </span>
                    <div className="actions">
                      <button
                        className="page-button"
                        aria-label="Предыдущая страница"
                        disabled={currentPage === 0}
                        onClick={() => setPage(currentPage - 1)}
                      >
                        ←
                      </button>
                      <span>
                        {currentPage + 1} / {pageCount}
                      </span>
                      <button
                        className="page-button"
                        aria-label="Следующая страница"
                        disabled={currentPage >= pageCount - 1}
                        onClick={() => setPage(currentPage + 1)}
                      >
                        →
                      </button>
                    </div>
                  </div>
                  <div className="approval-bar">
                    <div>
                      <Icon name={calc.status === 'APPROVED' ? 'check' : 'file'} />
                      <div>
                        <strong>
                          {calc.status === 'APPROVED'
                            ? `Утверждено: ${calc.approval?.approved_by}`
                            : 'Решение остаётся за менеджером'}
                        </strong>
                        <small>
                          {calc.status === 'APPROVED'
                            ? 'Экспорт доступен для текущей версии заказа.'
                            : 'Проверьте количество и объяснение каждой позиции.'}
                        </small>
                      </div>
                    </div>
                    <div className="actions">
                      <button
                        className="secondary"
                        disabled={calc.status !== 'APPROVED' || !!busy}
                        onClick={() => {
                          setExportSupplier(calc.supplier_groups[0]?.supplier_id || '');
                          show('export');
                        }}
                      >
                        <Icon name="download" size={17} />
                        Экспорт CSV
                      </button>
                      <button
                        className="primary"
                        disabled={approvingDisabled}
                        title={blocked ? 'Сначала уточните данные всех позиций' : undefined}
                        onClick={() => {
                          setAck([]);
                          show('approve');
                        }}
                      >
                        <Icon name="check" size={18} />
                        {calc.status === 'APPROVED' ? 'Заказ утверждён' : 'Утвердить заказ'}
                      </button>
                    </div>
                  </div>
                </section>
              )}
              {dataset && (
                <div className="support-grid">
                  <section className="panel support-card">
                    <div className="section-title">
                      <h3>Качество исходных данных</h3>
                      <button
                        disabled={!!busy}
                        className="text-button"
                        onClick={() => show('quality')}
                      >
                        Подробнее
                        <Icon name="arrow" size={15} />
                      </button>
                    </div>
                    {issueGroups.length ? (
                      <>
                        <p className="muted small">
                          {fmt(dataset.issues.length)} замечаний · {issueGroups.length} типов. Это
                          вопросы к источникам, а не количество товаров.
                        </p>
                        {issueGroups.slice(0, 3).map(([code, issue]) => (
                          <div className="quality-row" key={code}>
                            <span className="quality-dot" />
                            <span>{issue.message}</span>
                            <strong>{fmt(issue.count)}</strong>
                          </div>
                        ))}
                      </>
                    ) : (
                      <div className="quality-ok">
                        <span>
                          <Icon name="check" />
                        </span>
                        <div>
                          <strong>В исходном наборе нет замечаний</strong>
                          <p>Готовность конкретной позиции проверяется при расчёте.</p>
                        </div>
                      </div>
                    )}
                  </section>
                  <section className="panel support-card methodology">
                    <span className="eyebrow">ПРОЗРАЧНЫЙ РАСЧЁТ</span>
                    <h3>Спрос без случайных всплесков</h3>
                    <p>
                      Учитываем сезонность, рост, остаток и транзит. Разовые продажи и оценка
                      упущенного спроса видны в карточке товара.
                    </p>
                    <div className="method-steps">
                      <span>История</span>
                      <Icon name="arrow" size={15} />
                      <span>Регулярный спрос</span>
                      <Icon name="arrow" size={15} />
                      <span>Заказ</span>
                    </div>
                  </section>
                </div>
              )}
            </>
          )}
          <footer className="page-footer">
            <span>uitech · Планирование пополнения</span>
            <span>Данные хранятся локально · Экспорт после утверждения</span>
          </footer>
        </main>
      </div>

      {dialog === 'demo' && (
        <Modal title="Проверить на демо-сценарии" close={close} busy={!!busy}>
          {modalFeedback}
          <div className="modal-body">
            <p className="muted">
              Все данные вымышлены. Можно пройти весь путь от расчёта до экспорта без аккаунта.
            </p>
            <div className="demo-list">
              {demos.map(([key, title, text], i) => (
                <button key={key} disabled={!!busy} onClick={() => void demo(key)}>
                  <span className="demo-number">0{i + 1}</span>
                  <span>
                    <strong>{title}</strong>
                    <small>{text}</small>
                  </span>
                  <Icon name="arrow" />
                </button>
              ))}
            </div>
          </div>
        </Modal>
      )}
      {dialog === 'upload' && (
        <Modal title="Загрузить данные" close={close} busy={!!busy} wide>
          {modalFeedback}
          <Upload
            busy={!!busy}
            upload={(form) =>
              void perform('Импортируем и проверяем Excel…', async () => {
                const saved = await api<Summary>('/datasets/import', form);
                await refreshCatalog();
                await loadDataset(saved.dataset_id);
                setDialog(null);
                setSuccess('Набор сохранён. Проверьте качество данных перед расчётом.');
              })
            }
            normalized={(data, config) =>
              void perform('Проверяем набор…', async () => {
                const saved = await api<Summary>('/datasets', data);
                await refreshCatalog();
                await loadDataset(saved.dataset_id, config);
                setDialog(null);
                setSuccess('Нормализованный набор сохранён.');
              })
            }
          />
        </Modal>
      )}
      {dialog === 'settings' && dataset && params && (
        <Modal title="Параметры расчёта" close={close} wide busy={!!busy}>
          {modalFeedback}
          <Settings dataset={dataset} value={params} busy={!!busy} submit={calculate} />
        </Modal>
      )}
      {selected && (
        <Modal title="Объяснение рекомендации" close={close} wide busy={!!busy}>
          {modalFeedback}
          <ItemDetail
            key={`${selected.item_id}:${calc?.revision}`}
            item={selected}
            product={dataset?.products.find((p) => p.product_id === selected.product_id)}
            busy={!!busy}
            edit={(qty, reason) =>
              void perform('Сохраняем корректировку…', async () => {
                const result = await api<Calculation>(
                  `/calculations/${calc!.calculation_id}/items/${encodeURIComponent(selected.item_id)}`,
                  { expected_revision: calc!.revision, final_purchase_qty: qty, reason },
                  'PATCH',
                );
                remember(result);
                setSuccess('Корректировка сохранена. Заказ снова требует утверждения.');
              })
            }
          />
        </Modal>
      )}
      {dialog === 'quality' && dataset && (
        <Modal title="Качество и источники данных" close={close} wide busy={!!busy}>
          {modalFeedback}
          <div className="modal-body">
            <Kind kind={dataset.data_kind} />
            <h3>{dataset.name}</h3>
            <p className="muted">
              {fmt(dataset.products.length)} товаров · {fmt(dataset.issues.length)} замечаний.
              Исходные значения не исправляются автоматически.
            </p>
            <h3>Источники</h3>
            <div className="source-list">
              {dataset.sources.map((s) => (
                <div key={s.source_id}>
                  <Icon name="file" />
                  <div>
                    <strong>{s.file_name || s.role}</strong>
                    <small>{s.note}</small>
                  </div>
                </div>
              ))}
            </div>
            <h3>Замечания по типам</h3>
            {issueGroups.length ? (
              issueGroups.map(([code, issue]) => (
                <details className="quality-detail" key={code}>
                  <summary>
                    <strong>{code}</strong>
                    <span>{fmt(issue.count)}</span>
                  </summary>
                  <p>{issue.message}</p>
                  <ul>
                    {dataset.issues
                      .filter((i) => i.code === code)
                      .slice(0, 8)
                      .map((i, index) => (
                        <li key={index}>
                          {i.product_id || 'Весь набор'}: {i.message}
                          {i.source_refs?.map((r, j) => (
                            <small key={j}>
                              {r.sheet} {r.cell_range}
                            </small>
                          ))}
                        </li>
                      ))}
                  </ul>
                  {issue.count > 8 && <small>Показаны первые 8 замечаний этого типа.</small>}
                </details>
              ))
            ) : (
              <p className="notice green">Замечаний в исходном наборе нет.</p>
            )}
            <p className="muted small">
              Недостающие факты нужно уточнить у владельца данных. Исправленный нормализованный
              набор можно загрузить как новый JSON.
            </p>
          </div>
        </Modal>
      )}
      {dialog === 'approve' && calc && (
        <Modal title={`Утвердить заказ · версия ${calc.revision}`} close={close} busy={!!busy}>
          {modalFeedback}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void perform('Утверждаем заказ…', async () => {
                remember(
                  await api<Calculation>(`/calculations/${calc.calculation_id}/approve`, {
                    expected_revision: calc.revision,
                    approved_by: reviewer.trim(),
                    acknowledged_issue_codes: ack,
                  }),
                );
                setDialog(null);
                setSuccess('Заказ утверждён. Теперь можно скачать CSV по поставщикам.');
              });
            }}
          >
            <fieldset disabled={!!busy} className="modal-body">
              <Kind kind={calc.data_kind} />
              <p>
                {fmt(positive)} позиций с положительным количеством · {calc.summary.supplier_count}{' '}
                поставщиков. Утверждается весь расчёт, независимо от фильтров таблицы.
              </p>
              <label>
                Кто проверил заказ
                <input
                  required
                  value={reviewer}
                  placeholder="Имя менеджера"
                  onChange={(e) => setReviewer(e.target.value)}
                />
              </label>
              {warningCodes.length > 0 && (
                <>
                  <h3>Подтвердите проверку ограничений</h3>
                  {warningCodes.map((code) => (
                    <label className="ack-label" key={code}>
                      <input
                        type="checkbox"
                        checked={ack.includes(code)}
                        onChange={(e) =>
                          setAck(e.target.checked ? [...ack, code] : ack.filter((c) => c !== code))
                        }
                      />
                      <span>
                        {warningMap.get(code)}
                        <small>{code}</small>
                      </span>
                    </label>
                  ))}
                </>
              )}
              <p className="muted small">
                Утверждение сохраняет проверенную версию. Отправки поставщику не будет.
              </p>
              <button
                className="primary full"
                disabled={
                  blocked > 0 || !reviewer.trim() || warningCodes.some((c) => !ack.includes(c))
                }
                type="submit"
              >
                <Icon name="check" />
                Подтвердить утверждение
              </button>
            </fieldset>
          </form>
        </Modal>
      )}
      {dialog === 'export' && calc && (
        <Modal title="Экспорт заказа" close={close} busy={!!busy}>
          {modalFeedback}
          <form
            onSubmit={(e) => {
              e.preventDefault();
              void perform('Готовим CSV…', async () => {
                await downloadCsv(calc.calculation_id, exportSupplier, calc.revision);
                setDialog(null);
                setSuccess('Файл CSV передан браузеру для скачивания.');
              });
            }}
          >
            <fieldset className="modal-body" disabled={!!busy}>
              <p className="muted">
                Версия {calc.revision} · только положительные количества выбранного поставщика.
              </p>
              <label>
                Поставщик
                <select
                  required
                  value={exportSupplier}
                  onChange={(e) => setExportSupplier(e.target.value)}
                >
                  {calc.supplier_groups.map((g) => (
                    <option value={g.supplier_id} key={g.supplier_id}>
                      {supplierName(g.supplier_id)}
                    </option>
                  ))}
                </select>
              </label>
              <p className="muted small">
                CSV в UTF-8, разделитель «;». При открытии кодов с ведущими нулями выбирайте
                текстовый формат колонок. Совместимость с импортом 1С ещё не подтверждена.
              </p>
              <button className="primary full" type="submit">
                <Icon name="download" />
                Скачать CSV
              </button>
            </fieldset>
          </form>
        </Modal>
      )}
    </div>
  );
}

function Empty({ openDemo }: { openDemo: () => void }) {
  return (
    <section className="empty-workspace panel">
      <span className="empty-symbol">
        <Icon name="boxes" size={46} />
      </span>
      <p className="eyebrow">ОТ ПРОДАЖ К ЗАКАЗУ</p>
      <h2>
        Соберите следующий заказ
        <br />
        на основе данных
      </h2>
      <p>
        Загрузите продажи, остатки и поставки в пути
        <br />
        или начните с готового контрольного сценария.
      </p>
      <button className="primary" onClick={openDemo}>
        <Icon name="play" size={16} />
        Попробовать демо
      </button>
    </section>
  );
}
