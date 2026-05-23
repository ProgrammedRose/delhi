// =============================================================================
// ЛР2: Program.scala - параметризованная программа и две точки запуска
// =============================================================================
// Файл содержит:
//   1. IOFromState / IOFromStateLog - обёртки над Live-интерпретаторами,
//      приводящие Reader/State к единому F = IO
//   2. vendingProgram[F[_]]  - единая программа, параметризованная по F
//   3. LiveRun               - запуск с F = IO  (продакшн-интерпретаторы)
//   4. TestRun               - запуск с F = Id  (тест-интерпретаторы)
//   5. ProgramMain           - точка входа: запускает оба сценария
//
// Ключевой момент:
//   vendingProgram не знает про IO, State, Reader или Id.
//   Он знает только про F[_] с Monad + три алгебры.
//   Подмена интерпретаторов - единственное что меняется между Live и Test.
// =============================================================================


// =============================================================================
// 1. IO-ОБЁРТКИ НАД LIVE-ИНТЕРПРЕТАТОРАМИ
// =============================================================================
// Проблема: Live-интерпретаторы используют разные F:
//   LiveConfigInterpreter  → Reader[VendingConfig, _]
//   LiveLogInterpreter     → Writer[Vector[String], _]
//   LiveMachineInterpreter → State[MachineState, Result[_]]
//
// vendingProgram требует единого F для всех трёх алгебр.
// Решение: обернуть каждый Live-интерпретатор в IO,
// запуская Reader/State внутри IO(() => ...).
// Это тот же паттерн что AppState.runTransition в Scenario.scala из ЛР1.
//
// var для состояния изолирован внутри класса - снаружи не виден.

// ConfigAlgebra[IO] - запускает Reader прямо внутри IO.
class IOConfigInterpreter(cfg: VendingConfig) extends ConfigAlgebra[IO]:
  private val live = new LiveConfigInterpreter

  def priceOf(product: String): IO[Result[Int]]          = IO(() => live.priceOf(product).run(cfg))
  def canAcceptCoin(coin: Int): IO[Boolean]               = IO(() => live.canAcceptCoin(coin).run(cfg))
  def effectivePrice(p: String, h: Int): IO[Result[Int]] = IO(() => live.effectivePrice(p, h).run(cfg))
  def calculateChange(i: Int, p: Int): IO[Result[Change]] = IO(() => live.calculateChange(i, p).run(cfg))
  def canInsertMore(cur: Int, coin: Int): IO[Boolean]    = IO(() => live.canInsertMore(cur, coin).run(cfg))
  def availableProducts: IO[List[(String, Int)]]         = IO(() => live.availableProducts.run(cfg))


// LogAlgebra[IO] - лог пишется в изменяемый буфер, завёрнутый в IO.
// Writer здесь не используется: в IO-мире удобнее изменяемый буфер,
// как в TestLogInterpreter, но завёрнутый в IO-операции.
class IOLogInterpreter extends LogAlgebra[IO]:
  private var buffer: Vector[String] = Vector.empty

  private def append(msg: String): IO[Unit] = IO(() => buffer = buffer :+ msg)

  def log(message: String): IO[Unit]                               = append(message)
  def explainFailure(reason: String): IO[Unit]                     = append(s"[ОШИБКА ] $reason.")
  def explainCancel(returned: Int): IO[Unit]                       = append(s"[ОТМЕНА ] Возврат: ${returned} коп.")
  def explainRefill(product: String, amount: Int): IO[Unit]        = append(s"[СКЛАД  ] '$product' +$amount шт.")

  def explainInsert(coin: Int, accepted: Boolean, total: Int): IO[Unit] =
    if accepted then append(s"[МОНЕТА ] +$coin коп. Итого: $total коп.")
    else append(s"[МОНЕТА ] $coin коп. отклонена.")

  def explainPrice(product: String, base: Int, final_ : Int, hour: Int): IO[Unit] =
    if final_ < base then append(s"[ЦЕНА   ] '$product': $base → $final_ коп. (скидка в $hour:00)")
    else append(s"[ЦЕНА   ] '$product': $final_ коп.")

  def explainPurchase(product: String, inserted: Int, price: Int, changeResult: Result[Change]): IO[Unit] =
    changeResult match
      case Right(coins) => append(s"[УСПЕХ  ] '$product' куплен. Сдача: ${coins.sum} коп.")
      case Left(reason) => append(s"[ОТКАЗ  ] '$product': $reason.")

  def getLogs: IO[Vector[String]]  = IO(() => buffer)
  def clearLogs: IO[Unit]          = IO(() => buffer = Vector.empty)


// MachineAlgebra[IO] - запускает State-переходы внутри IO.
// var state изолирован внутри класса.
class IOMachineInterpreter(cfg: VendingConfig) extends MachineAlgebra[IO]:
  private var state: MachineState = MachineState.initial
  private val live = new LiveMachineInterpreter(cfg)

  // Вспомогательный метод: запустить State-переход, сохранить новое состояние.
  private def run[A](transition: State[MachineState, Result[A]]): IO[Result[A]] =
    IO(() =>
      val (result, newState) = transition.run(state)
      state = newState
      result
    )

  def insertCoin(coin: Int): IO[Result[Int]]                    = run(live.insertCoin(coin))
  def selectProduct(product: String, hour: Int): IO[Result[Change]] = run(live.selectProduct(product, hour))
  def cancelPurchase: IO[Result[Int]]                           = run(live.cancelPurchase)
  def refillProduct(product: String, amount: Int): IO[Result[Int]] = run(live.refillProduct(product, amount))
  //def getState: IO[Result[MachineState]] = run(live.getState)
  def getState: IO[MachineState] = IO(() => state)


// =============================================================================
// 2. ПАРАМЕТРИЗОВАННАЯ ПРОГРАММА
// =============================================================================
// vendingProgram - это будет демонстрационный сценарий, параметризованный по F.
// Не читает ввод с клавиатуры (это IO-специфика) - вместо этого
// выполняет фиксированный набор операций и печатает результат через logger.
//
// Именно это и есть суть Tagless Final:
//   - программа не знает про IO, State, Reader, Id
//   - она знает только про F[_]: Monad и три алгебры
//   - подмена интерпретаторов меняет поведение без изменения кода программы
//
// Контекстная граница F[_]: Monad означает:
//   «компилятор, найди given Monad[F] и подставь его неявно».
//   Это позволяет использовать for-comprehension на F.

def vendingProgram[F[_]: Monad](
                                 machine: MachineAlgebra[F],
                                 config:  ConfigAlgebra[F],
                                 logger:  LogAlgebra[F]
                               ): F[Unit] =
  for
    // Показываем доступные товары.
    products <- config.availableProducts
    _<- logger.log("=== Запуск торгового автомата ===")
    _  <- logger.log(s"Доступные товары: ${products.map(_._1).mkString(", ")}")
    
    _    <- logger.log("--- Сценарий: покупка колы ---")
    ins1 <- machine.insertCoin(200)
    _    <- logger.explainInsert(200, ins1.isRight, ins1.getOrElse(0))
    buy1  <- machine.selectProduct("cola", 14)
    _   <- logger.explainPurchase("cola", 200, 150, buy1)

    _  <- logger.log("--- Сценарий: покупка без денег ---")
    buy2  <- machine.selectProduct("water", 14)
    _     <- logger.explainPurchase("water", 0, 80, buy2)

    _  <- logger.log("--- Сценарий: недопустимая монета ---")
    ins2 <- machine.insertCoin(3)  // 3 копейки - нет такого номинала
    _ <- logger.explainInsert(3, ins2.isRight, ins2.getOrElse(0))

    _ <- logger.log("--- Сценарий: отмена покупки ---")
    ins3 <- machine.insertCoin(500)
    _ <- logger.explainInsert(500, ins3.isRight, ins3.getOrElse(0))
    cancel <- machine.cancelPurchase
    _ <- logger.explainCancel(cancel.getOrElse(0))

    // Пополнение склада.
    _        <- logger.log("--- Сценарий: пополнение склада ---")
    refill   <- machine.refillProduct("cola", 10)
    _        <- logger.explainRefill("cola", 10)

    // Итоговое состояние.
    /*stateRes <- machine.getState
    _        <- logger.log(s"Итоговое состояние: ${stateRes.map(s =>
      s"вставлено=${s.inserted}, выручка=${s.revenue}, склад=${s.stock}"
    ).getOrElse("недоступно")}")*/
    stateRes <- machine.getState
    _ <- logger.log(s"Итоговое состояние: вставлено=${stateRes.inserted}, " +
      s"выручка=${stateRes.revenue}, склад=${stateRes.stock}")

    // Получаем и выводим весь накопленный лог.
    logs <- logger.getLogs
    _ <- logger.log(s"Всего записей в логе: ${logs.size}")
  yield ()


// ==============================================
// LIVE-ЗАПУСК (F = IO)
// Создаём IO-обёртки над Live-интерпретаторами и запускаем vendingProgram[IO].
// Результат - IO[Unit], который выполняется через unsafeRun в ProgramMain.

object LiveRun:
  def run(cfg: VendingConfig): IO[Unit] =
    val machine = new IOMachineInterpreter(cfg)
    val config  = new IOConfigInterpreter(cfg)
    val logger  = new IOLogInterpreter

    // Запускаем программу - это IO[Unit], ещё не выполнено.
    val program = vendingProgram[IO](machine, config, logger)

    // После программы - напечатать накопленный лог.
    for
      _    <- IO.putStrLn("\n" + "=" * 50)
      _    <- IO.putStrLn("LIVE RUN (F = IO)")
      _    <- IO.putStrLn("=" * 50)
      _    <- program
      logs <- logger.getLogs
      _    <- IO.traverse_(logs.toList.map(IO.putStrLn))
    yield ()


// TEST-ЗАПУСК (F = Id)

// Создаём Test-интерпретаторы (Id) и запускаем vendingProgram[Id].
// F = Id означает что vendingProgram выполняется немедленно,
// как обычная функция без всяких там оберток.
// Лог собирается в буфере TestLogInterpreter.

object TestRun:
  def run(cfg: VendingConfig): Unit =
    val machine = new TestMachineInterpreter(cfg, MachineState.initial)
    val config  = new TestConfigInterpreter(cfg)
    val logger  = new TestLogInterpreter

    println("\n" + "=" * 50)
    println("TEST RUN (F = Id)")
    println("=" * 50)

    // vendingProgram[Id] возвращает Id[Unit] = Unit - выполняется сразу.
    // Никакого unsafeRun не нужно: Id не откладывает вычисление.
    vendingProgram[Id](machine, config, logger)

    // Читаем лог напрямую - getLogs: Id[Vector[String]] = Vector[String].
    val logs = logger.getLogs
    logs.foreach(println)


// Запускаем оба сценария с одной и той же конфигурацией.
// Вывод должен быть идентичным - нам это покажет, что vendingProgram
// не зависит от конкретного F - будет победа, если так.

object ProgramMain extends App:
  val cfg = DefaultConfig.config

  // Test-запуск: Id, выполняется сразу, без unsafeRun.
  TestRun.run(cfg)

  // Live-запуск: IO, выполняется через unsafeRun.
  LiveRun.run(cfg).unsafeRun()