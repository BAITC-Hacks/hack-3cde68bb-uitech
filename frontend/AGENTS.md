# Frontend

- React, TypeScript, Vite. `npm ci`, `npm run dev`, `npm run build`.
- E2E: при запущенных backend/frontend `npm run test:e2e` (Edge; другой канал через PLAYWRIGHT_CHANNEL). Реальный импорт — с UITECH_XLSX_DIR (Systeme) и UITECH_IEK_XLSX_DIR (IEK). Форматирование: `npm run format`.
- `/api` проксируется в backend на 127.0.0.1:8080; адрес можно задать через BACKEND_URL.
- В Docker frontend собирается через Node и обслуживается Nginx; nginx.conf проксирует /api в backend:8080. Полный запуск из корня: `./tools/start_docker.ps1`. Контекст сборки — корень, нужны синтетические fixtures/contract.
- Русский интерфейс. Реальные и синтетические данные явно различать.
- Количества рассчитывает backend. Не заменять неизвестные значения нулями и не суммировать разные единицы в KPI.
- Утверждение — отдельное действие пользователя с подтверждением предупреждений. Ручная правка отменяет прежнее утверждение.
- Реальные Excel/JSON не включать в frontend bundle. Демонстрации используют только fixtures/contract.
