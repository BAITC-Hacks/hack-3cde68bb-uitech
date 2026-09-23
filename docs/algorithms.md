# Алгоритмы --- Автоматизация формирования заказов поставщикам

## Общий pipeline

``` text
RAW DATA
   |
   v
Data Validation
   |
   v
Aggregation by SKU / warehouse / time
   |
   v
Bulk Order & Outlier Detection
   |
   v
REGULAR SALES
   |
   v
Stockout Detection & Lost Demand Estimation
   |
   v
CORRECTED DEMAND
   |
   v
Trend + Seasonality Forecasting
   |
   v
DEMAND FORECAST
   |
   v
Inventory / Replenishment Calculation
   |
   v
RECOMMENDED QUANTITY
   |
   v
MOQ / Supplier Rules
   |
   v
Risk / Urgency
   |
   v
Explanation Engine
   |
   v
Supplier Grouping
   |
   v
RESULT
```

## 1. Подготовка данных

Данные объединяются по SKU, складу и периоду. Проверяются пропуски,
некорректные значения и аномалии.

## 2. Базовая потребность

``` text
Target Stock = Forecast Demand + Safety Stock

Recommended Order =
max(0, Target Stock - Current Stock - In Transit)
```

Пример:

``` text
Forecast Demand = 600
Safety Stock    = 100
Current Stock   = 200
In Transit      = 150

Target Stock = 700
Recommended Order = 350
```

Изменение любого существенного входного параметра должно отражаться на
результате.

Точная политика Safety Stock и влияние категории исходным ТЗ не
определены и должны быть зафиксированы как проектное решение либо
уточнены у партнёра.

## 3. Сезонность и устойчивый рост

``` text
Sales History
     |
     +-- Level
     +-- Trend
     +-- Seasonality
             |
             v
       Demand Forecast
```

Пример сезонности:

``` text
Jan 100
Feb 100
Mar 110
Apr 130
May 250
Jun 550 <- сезонный пик
Jul 250
```

Прогноз должен отражать сезонный паттерн, а не простое среднее.

Устойчивый рост:

``` text
2023 -> 300
2024 -> 350
2025 -> 410
2026 -> 480
```

Для MVP можно рассмотреть Holt-Winters / Exponential Smoothing при
достаточной истории.

## 4. Компенсация stockout

``` text
RAW SALES:
12 11 10 0 0 0 12
         [stockout]

CORRECTED DEMAND:
12 11 10 11 11 11 12
```

Алгоритм: 1. Определить stockout. 2. Не считать продажи 0 в этот период
реальным спросом 0. 3. Оценить потенциальный спрос. 4. Получить
corrected demand. 5. Передать его в прогноз.

Оценка может учитывать историю SKU, соседние периоды, сезонность, тренд
и категорию.

## 5. Разовые крупные заказы

``` text
10 -> 11 -> 12 -> 500 -> 10 -> 11
                  ^
             bulk order
```

Возможные методы: - IQR; - MAD / robust Z-score; - rolling median; -
концентрация продажи на одном обезличенном клиенте; - анализ поведения
после всплеска.

Нужно отличать:

``` text
Разовый: 10 11 12 500 10 11
Тренд:   10 12 15 20 30 40 50
```

Исходная транзакция сохраняется, но для регулярного прогноза
используется очищенный спрос.

## 6. Правильный порядок

``` text
История продаж
      |
      v
Bulk / Outlier Detection
      |
      v
REGULAR SALES
      |
      v
Stockout Correction
      |
      v
CORRECTED DEMAND
      |
      v
Trend + Seasonality
      |
      v
DEMAND FORECAST
      |
      v
Recommended Order
```

## 7. Риск дефицита

``` text
Days of Stock =
Current Stock / Expected Daily Demand
```

Если `Days of Stock < Lead Time`, риск stockout повышается. Конкретные
пороги High / Medium / Low являются проектным решением.

## 8. MOQ и правила поставщика

``` text
Calculated Order = 347
MOQ / Pack Size  = 100
Final Order      = 400
```

Корректировка должна быть отражена в объяснении.

## 9. Explanation Engine

Для каждого SKU хранится trace:

``` json
{
  "sku": "ABC-123",
  "forecast_demand": 600,
  "safety_stock": 100,
  "current_stock": 200,
  "in_transit": 150,
  "seasonality_effect": 0.20,
  "trend_effect": 0.08,
  "estimated_lost_demand": 35,
  "excluded_bulk_sales": 500,
  "recommended_qty": 350,
  "urgency": "high"
}
```

На основе trace формируется краткое объяснение количества.

## 10. Группировка по поставщикам

``` text
Supplier A
+-- SKU-001 -> 350
+-- SKU-017 -> 120

Supplier B
+-- SKU-004 -> 800
+-- SKU-011 -> 90
```

## 11. Must Have тесты

### Test 1

Изменение остатка, товара в пути, категории, прогноза прироста и других
предусмотренных входов должно влиять на результат.

### Test 2

SKU с выраженной сезонностью должен получать сезонный прогноз, а не
простое среднее.

### Test 3

При stockout положительный оценённый lost demand должен повышать
расчётную потребность относительно сырых продаж.

### Test 4

Искусственный крупный разовый заказ не должен существенно увеличивать
регулярную рекомендацию.

### Test 5

Каждая строка результата содержит поставщика, количество и объяснение;
список доступен в разбивке по поставщику.

## 12. Архитектура расчётного ядра MVP

``` text
CSV / Excel / выгрузка 1С
          |
          v
     Data ingestion
          |
          v
 Python Calculation Engine
 +-- validation
 +-- anomaly detection
 +-- stockout correction
 +-- forecasting
 +-- replenishment
 +-- MOQ rules
 +-- urgency
 +-- explanations
          |
          v
         API
          |
          v
    Web Dashboard
```
