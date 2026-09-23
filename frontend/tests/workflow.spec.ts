import { test, expect, type Page } from '@playwright/test';
import { readFile, readdir } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

async function openDemo(page: Page, title: string) {
  await page.goto('/');
  await expect(page.getByText('Локальный сервер подключён')).toBeVisible();
  await page.getByRole('button', { name: 'Демо-сценарии', exact: true }).click();
  await page
    .getByRole('dialog')
    .getByRole('button', { name: new RegExp(title) })
    .click();
  await expect(page.getByRole('heading', { name: 'Рекомендации к закупке' })).toBeVisible();
  await expect(page.getByRole('dialog')).toHaveCount(0);
}

test('manager can filter, inspect, edit, approve, export and reopen a calculation', async ({
  page,
}, info) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  await openDemo(page, 'Заказы двум поставщикам');
  await expect(page.locator('tbody tr')).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'Экспорт CSV', exact: true })).toBeDisabled();
  await page.screenshot({ path: info.outputPath('dashboard.png'), fullPage: true });
  await page.getByLabel('Фильтр поставщика').selectOption('SUP_B');
  await expect(page.locator('tbody tr')).toHaveCount(1);
  await page.getByLabel('Фильтр поставщика').selectOption('');
  await page.locator('tbody tr').first().getByRole('button').first().click();
  let dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: 'Как получено количество' })).toBeVisible();
  await dialog.getByLabel(/^Количество,/).fill('160');
  await dialog
    .getByLabel('Причина изменения')
    .fill('Дополнительная потребность подтверждена менеджером');
  await dialog.getByRole('button', { name: 'Сохранить корректировку' }).click();
  await expect(dialog.getByText(/Предыдущая правка: Дополнительная/)).toBeVisible();
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await page.getByRole('button', { name: 'Утвердить заказ', exact: true }).click();
  await dialog.getByLabel('Кто проверил заказ').fill('Тестовый менеджер');
  await dialog.getByRole('button', { name: 'Подтвердить утверждение' }).click();
  await expect(page.getByText('Утверждено: Тестовый менеджер')).toBeVisible();
  await page.getByRole('button', { name: 'Экспорт CSV', exact: true }).click();
  await dialog.getByRole('combobox', { name: 'Поставщик', exact: true }).selectOption('SUP_A');
  const downloadPromise = page.waitForEvent('download');
  await dialog.getByRole('button', { name: 'Скачать CSV' }).click();
  const download = await downloadPromise;
  const csvPath = info.outputPath('approved.csv');
  await download.saveAs(csvPath);
  const csv = await readFile(csvPath, 'utf8');
  expect(csv).toContain('"150";"160"');
  expect(csv).not.toContain('"SUP_B"');
  await page.reload();
  await expect(page.getByText('Утверждено: Тестовый менеджер')).toBeVisible();
  await page.locator('tbody tr').first().getByRole('button').first().click();
  await dialog.getByLabel(/^Количество,/).fill('170');
  await dialog.getByLabel('Причина изменения').fill('Повторная корректировка');
  await dialog.getByRole('button', { name: 'Сохранить корректировку' }).click();
  await expect(dialog.getByText('Предыдущая правка: Повторная корректировка')).toBeVisible();
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Экспорт CSV', exact: true })).toBeDisabled();
  expect(errors).toEqual([]);
});

test('one-off sales are explained and the mobile layout fits the viewport', async ({
  page,
}, info) => {
  await openDemo(page, 'Разовая крупная продажа');
  await page.locator('tbody tr').first().getByRole('button').first().click();
  const dialog = page.getByRole('dialog');
  await expect(dialog.getByRole('heading', { name: /Исключённые всплески/ })).toBeVisible();
  await expect(dialog.getByText(/Разовая крупная накладная/)).toBeVisible();
  await page.screenshot({ path: info.outputPath('explanation.png'), fullPage: true });
  await dialog.getByRole('button', { name: 'Закрыть', exact: true }).click();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.screenshot({ path: info.outputPath('mobile.png'), fullPage: true });
});

test('unknown stock blocks approval and export', async ({ page }) => {
  await openDemo(page, 'Неизвестный остаток');
  await expect(page.getByRole('button', { name: 'Утвердить заказ', exact: true })).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Экспорт CSV', exact: true })).toBeDisabled();
  await page.locator('tbody tr').first().getByRole('button').first().click();
  await expect(
    page.getByRole('dialog').getByText('CURRENT_STOCK_REQUIRED', { exact: true }),
  ).toBeVisible();
  await expect(page.getByRole('button', { name: 'Сохранить корректировку' })).toHaveCount(0);
});

test('JSON upload preserves calculation parameters and runs through the form', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('Локальный сервер подключён')).toBeVisible();
  await page.getByRole('button', { name: 'Загрузить данные', exact: true }).click();
  await page.getByText('Загрузить нормализованный JSON или контрольный пример').click();
  await page
    .getByLabel('JSON-файл')
    .setInputFiles(
      fileURLToPath(new URL('../../fixtures/contract/01_baseline.json', import.meta.url)),
    );
  await expect(page.getByText('Нормализованный набор сохранён.')).toBeVisible();
  await page.getByRole('button', { name: 'Настроить расчёт', exact: true }).click();
  await expect(page.getByLabel('Срок поставки SUP_A')).toHaveValue('7');
  await page.getByRole('button', { name: 'Рассчитать рекомендации', exact: true }).click();
  await expect(page.locator('tbody tr').first()).toContainText('150');
});

test('real Excel import exposes source quality without inventing a recommendation', async ({
  page,
}) => {
  const dir = process.env.UITECH_XLSX_DIR;
  test.skip(!dir, 'Set UITECH_XLSX_DIR to six extracted Systeme XLSX files');
  const files = (await readdir(dir!))
    .filter((n) => n.endsWith('.xlsx'))
    .map((n) => path.join(dir!, n));
  await page.goto('/');
  await expect(page.getByText('Локальный сервер подключён')).toBeVisible();
  await page.getByRole('button', { name: 'Загрузить данные', exact: true }).click();
  await page.getByLabel('Выбрать Excel-файлы', { exact: true }).setInputFiles(files);
  await page.getByLabel('Название набора').fill('Systeme Electric 22.09.2026');
  await page.getByRole('button', { name: 'Импортировать 6 файлов' }).click();
  await expect(
    page.getByText('Набор сохранён. Проверьте качество данных перед расчётом.'),
  ).toBeVisible();
  await expect(page.getByText(/724 товаров/).first()).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Рекомендации к закупке' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Качество данных', exact: true }).click();
  await expect(
    page.getByRole('dialog').getByText('MONTHLY_SALES_MISMATCH', { exact: true }),
  ).toBeVisible();
});
