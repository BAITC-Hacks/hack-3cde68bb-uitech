# Алгоритмы --- Автоматизация формирования заказов поставщикам

## 1\. Pipeline

``` text
RAW EXCEL
   |
   v
SOURCE / SCHEMA DETECTION
   |
   v
DATA MAPPING \\\& NORMALIZATION
   |
   v
DATA VALIDATION
   |
   v
TRANSACTION NORMALIZATION
   |
   v
OUTLIER / BULK ORDER DETECTION
   |
   v
REGULAR SALES
   |
   v
ESTIMATED STOCKOUT / LOST DEMAND
   |
   v
CORRECTED DEMAND
   |
   v
TREND + SEASONALITY FORECAST
   |
   v
DEMAND FORECAST
   |
   v
PROJECTED INVENTORY
(current stock + dated in-transit)
   |
   v
RAW ORDER QTY
   |
   v
MOQ / PACK RULES
   |
   v
FINAL ORDER QTY
   |
   v
RISK / URGENCY
   |
   v
EXPLANATION
   |
   v
GROUP BY SUPPLIER
```

## 2\. Нормализация

Разные поля приводятся к общей схеме:

``` text
Код 1с / Номенклатура.Код / Код -> sku\\\_id
```

Рекомендуемые внутренние поля: `sku\\\_id`, `supplier\\\_id`, `supplier\\\_sku`,
`product\\\_name`, `warehouse\\\_id`, `transaction\\\_date`, `sales\\\_qty`,
`current\\\_stock`, `in\\\_transit\\\_qty`, `expected\\\_arrival\\\_date`, `moq`,
`pack\\\_size`, `category`, `client\\\_id` (если есть), `document\\\_id`.

## 3\. Валидация транзакций

Проверяются пропуски, дубликаты, даты, SKU, числовые значения и тип
операции.

Если исходные продажи записаны отрицательными количествами, нельзя
безусловно применять `abs()`:

``` text
quantity < 0
   |
   v
определить тип операции
   |
   +-- продажа -> abs(quantity)
   +-- возврат/прочее -> отдельная обработка
```

## 4\. Агрегация

После нормализации транзакции агрегируются по
`SKU + warehouse + time period`.

## 5\. Разовые крупные заказы

Сигналы: - IQR; - MAD / robust Z-score; - rolling median; - высокая
концентрация на одном client\_id, если он доступен; - концентрация в
одном document\_id как fallback; - возврат спроса к обычному уровню после
всплеска.

``` text
One-time: 10 11 12 500 10 11
Trend:    10 12 15 20 30 40 50
```

Фактическая транзакция сохраняется, но для forecast используется
очищенный `regular\\\_demand`.

## 6\. Estimated Stockout / Lost Demand

Из-за месячной гранулярности остатков stockout трактуется как оценочный.

``` text
low/zero stock
+ sales drop
+ normal demand before/after
        |
        v
estimated stockout
        |
        v
estimated lost demand
```

Базово:

``` text
Estimated Lost Demand =
Expected Demand - Observed Sales

Corrected Demand =
Observed Sales + Estimated Lost Demand
```

Ожидаемый спрос оценивается по истории, соседним периодам, сезонности и
тренду.

## 7\. Forecast Engine

``` text
Corrected Demand
      |
      +-- Level
      +-- Trend
      +-- Seasonality
      |
      v
Forecast
```

При достаточной истории используется сезонность SKU. При недостаточной
истории допускается fallback на сезонный профиль поставщика/категории,
но только после проверки применимости.

Для MVP можно сравнить Holt-Winters / Exponential Smoothing с
baseline-моделями.

## 8\. Backtesting

Используется walk-forward, без случайного перемешивания временного ряда:

``` text
TRAIN -> следующий период TEST
TRAIN + предыдущий период -> следующий TEST
...
```

Сравниваются: - naive; - seasonal naive; - основная модель.

Метрики: MAE, WAPE, RMSE, Forecast Bias.

## 9\. Projected Inventory

Если известны даты поступления:

``` text
Projected Stock(t) =
Projected Stock(t-1)
+ Arrivals(t)
- Forecast Demand(t)
```

Это позволяет выявлять дефицит до прихода следующей партии, а не просто
вычитать весь `In Transit` из потребности.

## 10\. Расчёт базовой потребности

Упрощённо:

``` text
Target Stock = Forecast Demand + Safety Stock

Raw Order Qty =
max(0, Target Stock - Available/Projected Inventory)
```

При наличии дат поставок `Available/Projected Inventory` рассчитывается
по временной шкале.

Точная политика Safety Stock должна быть отдельно формализована.

## 11\. MOQ / кратность

После получения raw quantity применяется правило поставщика:

``` text
Final Order Qty =
ceil(Raw Order Qty / PackOrMOQ) \\\* PackOrMOQ
```

если конкретная бизнес-семантика поля действительно означает округление
до кратности. Для минимальной партии без требования кратности правило
может отличаться и должно задаваться отдельно.

Пример:

``` text
Raw = 347
Pack = 100
Final = 400
```

## 12\. Риск дефицита

``` text
Days of Stock =
Current Stock / Expected Daily Demand
```

С учётом поставок более точный вариант оценивает момент, когда
`Projected Stock <= 0`.

Срочность определяется сравнением даты потенциального дефицита с
ближайшей датой поступления / lead time.

## 13\. Explanation Trace

Для каждого SKU сохраняются входы и преобразования:

``` json
{
  "sku\\\_id": "A-101",
  "forecast\\\_demand": 600,
  "current\\\_stock": 200,
  "in\\\_transit": 150,
  "next\\\_arrival": "2026-10-01",
  "estimated\\\_lost\\\_demand": 35,
  "excluded\\\_bulk\\\_sales": 500,
  "raw\\\_order\\\_qty": 347,
  "moq\\\_or\\\_pack": 100,
  "final\\\_order\\\_qty": 400,
  "urgency": "high"
}
```

Объяснение строится из этого trace, а не генерируется независимо от
расчёта.

## 14\. Группировка

``` text
IEK
+-- SKU A -> 400
+-- SKU B -> 120

System Electric
+-- SKU C -> 300
+-- SKU D -> 80
```

## 15\. Must Have тесты

### Test 1 --- влияние источников

Изменить stock, in-transit или другой предусмотренный вход и проверить
изменение рекомендации.

### Test 2 --- сезонность

SKU с сезонным пиком должен отражать его в forecast, а не давать плоское
среднее.

### Test 3 --- stockout

Для оценочного stockout сравнить raw forecast и corrected forecast. При
положительном lost demand скорректированная потребность должна быть
выше.

### Test 4 --- bulk order

Добавить синтетический крупный заказ. Регулярная рекомендация не должна
существенно вырасти. При наличии client\_id присвоить spike одному
обезличенному клиенту; иначе отдельно протестировать синтетический
client\_id.

### Test 5 --- итоговый список

Каждая строка содержит поставщика, final quantity и explanation; список
фильтруется по поставщику.

## 16\. Дополнительные тесты фактических данных

### In-transit timing

Одинаковое количество в пути с разными датами прихода должно по-разному
влиять на риск дефицита.

### MOQ

Изменение MOQ/кратности должно корректно менять final quantity, но не
raw need.

### Mapping

Один и тот же SKU из разных файлов после нормализации должен связываться
с одной внутренней сущностью.

### Transaction sign

Возврат не должен превращаться в продажу из-за безусловного `abs()`.

## 17\. Архитектура MVP

``` text
IEK Excel -------+
                 |
System Electric -+--> ETL / Normalization
                 |          |
1C / other ------+          v
                     Canonical Data Model
                             |
                             v
                     Calculation Engine
                     + anomaly detection
                     + stockout estimation
                     + forecasting
                     + projected inventory
                     + replenishment
                     + MOQ rules
                     + urgency
                     + explanation trace
                             |
                             v
                            API
                             |
                             v
                         Dashboard
                             |
                             v
                       Manager Approval
                             |
                             v
                           Export
```

