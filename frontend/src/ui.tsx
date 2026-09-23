import { useEffect, useId, useRef, type ReactNode } from 'react';
import type { Issue } from './types';

const inputMessages: Record<string, string> = {
  CURRENT_STOCK_REQUIRED: 'Укажите подтверждённый свободный остаток на дату расчёта.',
  NEGATIVE_STOCK: 'В источнике отрицательный остаток. Уточните данные склада.',
  INBOUND_COVERAGE_REQUIRED: 'Подтвердите полноту открытых поставок для склада на дату расчёта.',
  INBOUND_DATE_OR_STATUS_REQUIRED:
    'Уточните даты и статус ожидаемых поставок, включая просроченные.',
  PURCHASE_UNITS_REQUIRED: 'Укажите единицы закупки, коэффициент пересчёта, MOQ и кратность.',
  SALES_COVERAGE_REQUIRED: 'Подтвердите полноту продаж за выбранный период.',
  AVAILABILITY_REQUIRED:
    'Нужна история наличия товара, чтобы отличить нулевые продажи от отсутствия запаса.',
  SUPPLIER_POLICY_REQUIRED: 'Укажите срок поставки и период формирования заказа этому поставщику.',
  CATEGORY_POLICY_REQUIRED: 'Назначьте категорию товара и её правила страхового запаса.',
  RETURN_POLICY_REQUIRED: 'Согласуйте правила учёта возвратов перед расчётом спроса.',
  SEASONAL_PROFILE_REQUIRED: 'Добавьте сезонный профиль или выберите его оценку по истории.',
  NO_AVAILABLE_HISTORY: 'В выбранном периоде нет подтверждённых дней наличия для оценки спроса.',
  INSUFFICIENT_SEASONAL_HISTORY:
    'Для оценки сезонности недостаточно истории: нужны два полных годовых цикла.',
  INSUFFICIENT_TREND_HISTORY:
    'Для оценки тренда нужны минимум три полных месяца с подтверждённым наличием.',
};
export const issueMessage = (issue: Issue) => inputMessages[issue.code] || issue.message;

export const fmt = (n: number | null | undefined) =>
  n == null ? '—' : new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 }).format(n);
export const date = (s: string | null | undefined) =>
  s
    ? new Intl.DateTimeFormat('ru-RU', { day: 'numeric', month: 'short', year: 'numeric' }).format(
        new Date(s.slice(0, 10) + 'T12:00:00'),
      )
    : '—';
export function Icon({ name, size = 20 }: { name: string; size?: number }) {
  const paths: Record<string, ReactNode> = {
    boxes: (
      <>
        <path d="m3 7 9-5 9 5v10l-9 5-9-5Z" />
        <path d="m3 7 9 5 9-5M12 12v10M7 4.8l10 5.5" />
      </>
    ),
    grid: (
      <>
        <rect x="3" y="3" width="7" height="7" rx="1.5" />
        <rect x="14" y="3" width="7" height="7" rx="1.5" />
        <rect x="3" y="14" width="7" height="7" rx="1.5" />
        <rect x="14" y="14" width="7" height="7" rx="1.5" />
      </>
    ),
    database: (
      <>
        <ellipse cx="12" cy="5" rx="8" ry="3" />
        <path d="M4 5v14c0 4 16 4 16 0V5M4 12c0 4 16 4 16 0" />
      </>
    ),
    sliders: (
      <>
        <path d="M4 7h7m5 0h4M4 17h3m5 0h8" />
        <circle cx="13" cy="7" r="2" />
        <circle cx="9" cy="17" r="2" />
      </>
    ),
    upload: (
      <>
        <path d="M12 16V3m-5 5 5-5 5 5M4 16v4a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-4" />
      </>
    ),
    download: (
      <>
        <path d="M12 3v13m-5-5 5 5 5-5M4 17v3a1 1 0 0 0 1 1h14a1 1 0 0 0 1-1v-3" />
      </>
    ),
    arrow: <path d="M5 12h14m-5-5 5 5-5 5" />,
    check: <path d="m5 12 4 4L19 6" />,
    close: <path d="m6 6 12 12M6 18 18 6" />,
    search: (
      <>
        <circle cx="10.5" cy="10.5" r="6.5" />
        <path d="m16 16 5 5" />
      </>
    ),
    alert: (
      <>
        <path d="m12 3 10 17H2Z" />
        <path d="M12 9v5m0 3h.01" />
      </>
    ),
    trend: (
      <>
        <path d="m3 17 6-6 4 4 8-10m-6 0h6v6" />
      </>
    ),
    clock: (
      <>
        <circle cx="12" cy="12" r="9" />
        <path d="M12 7v5l3 2" />
      </>
    ),
    play: <path d="m8 4 12 8-12 8Z" />,
    file: (
      <>
        <path d="M14 2H5v20h14V7Zm0 0v5h5M8 12h8M8 16h8" />
      </>
    ),
    refresh: (
      <>
        <path d="M20 7v5h-5M4 17v-5h5" />
        <path d="M6 7a7 7 0 0 1 12-2l2 7M4 12l2 7a7 7 0 0 0 12-2" />
      </>
    ),
  };
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.65"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
    >
      {paths[name] || paths.boxes}
    </svg>
  );
}
const statusNames: Record<string, string> = {
  READY: 'Готово к заказу',
  ASSUMED: 'С допущениями',
  NEEDS_INPUT: 'Нужны данные',
  EXCLUDED_BY_POLICY: 'Закупка запрещена',
  APPROVED: 'Утверждено',
  DRAFT: 'Черновик',
  PARTIAL: 'Есть вопросы',
};
export function Badge({ status }: { status: string }) {
  return (
    <span
      className={`badge ${['READY', 'APPROVED'].includes(status) ? 'green' : ['NEEDS_INPUT', 'PARTIAL', 'ASSUMED'].includes(status) ? 'amber' : 'neutral'}`}
    >
      <span className="dot" />
      {statusNames[status] || status}
    </span>
  );
}
export function Kind({ kind }: { kind: string }) {
  return (
    <span className={`kind ${kind === 'synthetic' ? 'demo-kind' : ''}`}>
      {kind === 'synthetic' ? 'Демо · синтетические данные' : 'Реальные данные'}
    </span>
  );
}
export function Modal({
  title,
  children,
  close,
  wide = false,
  busy = false,
}: {
  title: string;
  children: ReactNode;
  close: () => void;
  wide?: boolean;
  busy?: boolean;
}) {
  const ref = useRef<HTMLDialogElement>(null);
  const id = useId();
  useEffect(() => {
    ref.current?.showModal();
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);
  return (
    <dialog
      ref={ref}
      className={wide ? 'modal wide' : 'modal'}
      aria-labelledby={id}
      onCancel={(e) => {
        e.preventDefault();
        if (!busy) close();
      }}
    >
      <header className="modal-head">
        <h2 id={id}>{title}</h2>
        <button className="icon-button" onClick={close} disabled={busy} aria-label="Закрыть">
          <Icon name="close" />
        </button>
      </header>
      {children}
    </dialog>
  );
}
export function Chart({
  rows,
  lines,
  label,
}: {
  rows: { date: string; [key: string]: string | number }[];
  lines: { key: string; name: string; color: string }[];
  label: string;
}) {
  if (!rows.length) return <div className="empty-chart">Для графика нужны данные расчёта.</div>;
  const visible = rows.slice(-90);
  const width = 760,
    height = 180,
    pad = 36;
  const max = Math.max(1, ...visible.flatMap((r) => lines.map((l) => Number(r[l.key] || 0))));
  const x = (i: number) => pad + (i / Math.max(1, visible.length - 1)) * (width - pad * 2);
  const y = (v: number) => height - pad - (v / max) * (height - pad * 1.5);
  return (
    <div className="chart">
      <div className="chart-legend">
        {lines.map((l) => (
          <span key={l.key}>
            <i style={{ background: l.color }} />
            {l.name}
          </span>
        ))}
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={label}>
        {[0, 0.5, 1].map((t) => (
          <g key={t}>
            <line
              x1={pad}
              y1={y(max * t)}
              x2={width - pad}
              y2={y(max * t)}
              stroke="#e7ece8"
              strokeDasharray="4 5"
            />
            <text x={pad - 8} y={y(max * t) + 4} textAnchor="end">
              {fmt(max * t)}
            </text>
          </g>
        ))}
        {lines.map((l) => (
          <g key={l.key}>
            <polyline
              points={visible.map((r, i) => `${x(i)},${y(Number(r[l.key] || 0))}`).join(' ')}
              fill="none"
              stroke={l.color}
              strokeWidth="2.5"
              strokeLinejoin="round"
            />
            {visible.map((r, i) => (
              <circle key={r.date} cx={x(i)} cy={y(Number(r[l.key] || 0))} r="3" fill={l.color}>
                <title>
                  {date(r.date)} · {l.name}: {fmt(Number(r[l.key] || 0))}
                </title>
              </circle>
            ))}
          </g>
        ))}
        <text x={pad} y={height - 6}>
          {date(visible[0].date)}
        </text>
        <text x={width - pad} y={height - 6} textAnchor="end">
          {date(visible.at(-1)!.date)}
        </text>
      </svg>
      {rows.length > 90 && <small>Показаны последние 90 дней.</small>}
    </div>
  );
}
