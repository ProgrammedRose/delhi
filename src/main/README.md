# ЛР1: Монады — Торговый автомат

## Структура проекта

```
src/
├── Monads.scala    — Блок 0: инфраструктура монад (Monad, Reader, Writer, State, IO)
├── Domain.scala    — Блоки 1–3: логика автомата (Reader, Writer, State)
└── Scenario.scala  — Блок 4: IO-сценарий и точка входа (Main)
```

### Что в каком файле

| Файл | Содержимое |
|---|---|
| `Monads.scala` | `trait Monad[M[_]]`, `Reader`, `Writer`, `State`, `IO` и их `given`-экземпляры |
| `Domain.scala` | `VendingConfig`, `VendingReader` (Блок 1), `VendingWriter` (Блок 2), `MachineState`, `VendingMachine` (Блок 3), `DefaultConfig` |
| `Scenario.scala` | `Display`, `ScenarioStep`, `AppState`, `ScenarioLoop`, `Main` (Блок 4) |

---

## Как запустить

### Вариант 1 — Scala CLI (рекомендуется)

```bash
# Установка Scala CLI: https://scala-cli.virtuslab.org/install
scala-cli run src/Monads.scala src/Domain.scala src/Scenario.scala
```

### Вариант 2 — scalac + scala вручную

```bash
# Компиляция всех файлов в директорию out/
scalac -d out src/Monads.scala src/Domain.scala src/Scenario.scala

# Запуск
scala -cp out Main
```

### Вариант 3 — sbt

Создайте файл `build.sbt`:

```sbt
scalaVersion := "3.3.1"
```

Положите `.scala`-файлы в `src/main/scala/`, затем:

```bash
sbt run
```

---

## Команды автомата

| Команда | Действие |
|---|---|
| `menu` | Показать список товаров с ценами (со скидками если после 18:00) |
| `insert` | Внести монету (вводится номинал в копейках) |
| `buy` | Выбрать товар и совершить покупку |
| `cancel` | Отменить транзакцию и вернуть внесённые деньги |
| `refill` | Пополнить склад (сервисная команда) |
| `status` | Показать полное состояние автомата (склад, касса, выручка) |
| `quit` | Завершить работу |

---

## Конфигурация

Конфигурация автомата задаётся в `DefaultConfig` в `Domain.scala`:

- **Товары и цены**: cola 1.50 р., water 0.80 р., juice 1.20 р., coffee 2.00 р.
- **Принимаемые монеты**: 10, 50, 100, 200, 500, 1000, 2000, 5000 копеек
- **Лимит внесения**: 100 рублей за раз
- **Скидка**: сок (`juice`) после 18:00 — 10% дешевле

Чтобы изменить конфигурацию — отредактируйте `DefaultConfig.config` в `Domain.scala`.

---

## Архитектура: как используются монады

```
Reader[VendingConfig, A]
  └─ Блок 1: priceOf, canAcceptCoin, effectivePrice, calculateChange
     Конфигурация читается один раз в Main и передаётся явно в переходы.

Writer[Vector[String], A]
  └─ Блок 2: explainInsert, explainPurchase, explainFailure, ...
     Каждый переход возвращает результат вместе с логом операции.

State[MachineState, A]
  └─ Блок 3: insertCoin, selectProduct, cancelPurchase, refillProduct
     Состояние автомата никогда не хранится в глобальной переменной —
     каждый переход принимает старое состояние и возвращает новое.

IO[A]
  └─ Блок 4: весь пользовательский ввод-вывод описан как IO-значения.
     unsafeRun вызывается ровно один раз — в Main.
```
