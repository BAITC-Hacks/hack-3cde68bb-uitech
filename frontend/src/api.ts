export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
  ) {
    super(message);
  }
}
export async function api<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const multipart = body instanceof FormData;
  let response: Response;
  try {
    response = await fetch('/api/v1' + path, {
      method: method || (body === undefined ? 'GET' : 'POST'),
      headers:
        body !== undefined && !multipart ? { 'Content-Type': 'application/json' } : undefined,
      body: body === undefined ? undefined : multipart ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(180_000),
    });
  } catch {
    throw new ApiError(
      'Сервер не отвечает. Проверьте, запущен ли backend, и повторите действие.',
      0,
    );
  }
  if (!response.ok) {
    const data = await response.json().catch(() => null);
    throw new ApiError(
      data?.error?.message || `Не удалось выполнить действие (${response.status}).`,
      response.status,
    );
  }
  return response.json();
}
export async function downloadCsv(id: string, supplier: string, revision: number) {
  const response = await fetch(
    `/api/v1/calculations/${encodeURIComponent(id)}/export?supplier_id=${encodeURIComponent(supplier)}&format=csv&revision=${revision}`,
    { signal: AbortSignal.timeout(60_000) },
  );
  if (!response.ok) {
    const data = await response.json().catch(() => null);
    throw new ApiError(data?.error?.message || 'Не удалось скачать заказ.', response.status);
  }
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = `uitech-${supplier.replace(/[^\w-]/g, '_')}-r${revision}.csv`;
  document.body.append(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
