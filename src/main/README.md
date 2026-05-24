# ЛР2 - Tagless Final

## Структура проекта

```
src/
    Monads.scala - Блок 0: инфраструктура монад (Monad, Reader, Writer, State, IO)
    Domain.scala - Блоки 1-3: предметные типы (VendingConfig, MachineState, DefaultConfig)
    Algebras.scala - алгебры Tagless Final 
    Interpretators.scala — IO-интерпретаторы для всех алгебр
    Scenario.scala - Блок 4: IO-сценарий и точка входа (Main)
```

### Что в каком файле

| Файл | Содержимое                                                                                                                      |
|---|---------------------------------------------------------------------------------------------------------------------------------|
| `Monads.scala` | `trait Monad[M[_]]`, `Reader`, `Writer`, `State`, `IO` и их `given`-ы                                                           |
| `Domain.scala` | `VendingConfig`, `VendingReader` (Блок 1), `VendingWriter` (Блок 2), `MachineState`, `VendingMachine` (Блок 3), `DefaultConfig` |
| `Scenario.scala` | `Display`, `ScenarioStep`, `AppState`, `ScenarioLoop`, `Main` (Блок 4)                                                          |

### Монады 
Они те же монады, что в первой лабе: `Reader`, `Writer`, `State`, `IO`.
Используются внутри интерпретаторов, но не видны в коде сценария как раньше было.

### Алгебры
Четыре трейта с параметром `F[_]` описывают что умеет делать наша система.

| Алгебра | Ответственность |
|---|---|
| `ConfigAlgebra[F]` | Чтение конфигурации (цены, скидки, лимиты) |
| `LogAlgebra[F]` | Накопление и вывод лога операций |
| `MachineAlgebra[F]` | Переходы состояния автомата |
| `ConsoleAlgebra[F]` | Консольный ввод-вывод |

### Интерпретаторы
Конкретные реализации алгебр для F = IO описывают как это делается.

| Класс | Алгебра | 
|---|---|
| `IOConfigInterpreter(cfg)` | `ConfigAlgebra[IO]` | 
| `IOLogInterpreter` | `LogAlgebra[IO]` |
| `IOMachineInterpreter(cfg)` | `MachineAlgebra[IO]` |
| `IOConsoleInterpreter` | `ConsoleAlgebra[IO]` |


## Ключевое отличие от первой лабы

| | ЛР1                          | ЛР2                                      |
|---|------------------------------|------------------------------------------|
| Сигнатуры функций | `IO[Unit]`, `State[...]`     | `F[Unit]`                                |
| Замена реализации | Тут везде переписать функции | А тут просто подать другой интерпретатор |
| Состояние | Было `AppState` + `State`    | Стало внутри `IOMachineInterpreter`      |

## Как запустить

Создать файл `build.sbt` или клонируйте уже существующий из репозитория.

Положите `.scala`-файлы в `src/main/scala/`, затем:

```bash
sbt run
```

---

## Команды автомата

| Команда | Действие                                                        |
|---|-----------------------------------------------------------------|
| `menu` | Показать список товаров с ценами (со скидками если после 18:00) |
| `insert` | Внести монету (вводится номинал в копейках)                     |
| `buy` | Выбрать товар и совершить покупку                               |
| `cancel` | Отменить транзакцию и вернуть внесённые деньги                  |
| `refill` | Пополнить склад (сервисная команда)                             |
| `status` | Показать полное состояние автомата - склад, касса, выручка     |
| `quit` | Завершить работу                                                |


## Конфигурация

Конфигурация автомата задаётся в `DefaultConfig` в `Domain.scala`:



## Архитектура: как используются монады

```
Reader[VendingConfig, A]
  Блок 1: priceOf, canAcceptCoin, effectivePrice, calculateChange
     Конфигурация читается один раз в Main и передаётся явно в переходы.

Writer[Vector[String], A]
  Блок 2: explainInsert, explainPurchase, explainFailure, ...
     Каждый переход возвращает результат вместе с логом операции.

State[MachineState, A]
  Блок 3: insertCoin, selectProduct, cancelPurchase, refillProduct
     Состояние автомата никогда не хранится в глобальной переменной -
     каждый переход принимает старое состояние и возвращает новое.

IO[A]
  Блок 4: весь пользовательский ввод-вывод описан как IO-значения.
     unsafeRun вызывается ровно один раз - в Main.
```
