// =============================================================================
// ЛР2: Интерпретаторы — конкретные реализации алгебр для F = IO
// =============================================================================
// Каждый интерпретатор реализует одну алгебру для конкретного F.
// Все четыре используют F = IO — единый эффект для всей программы.
//
// IO*Interpreter оборачивает логику в IO(() => ...) там где нужно,
// либо напрямую запускает State-переходы и Reader-вычисления внутри IO.
//
//   IOConfigInterpreter   — ConfigAlgebra[IO]
//   IOLogInterpreter      — LogAlgebra[IO]
//   IOMachineInterpreter  — MachineAlgebra[IO]
//   IOConsoleInterpreter  — ConsoleAlgebra[IO]
// =============================================================================


// -----------------------------------------------------------------------------
// IOConfigInterpreter — ConfigAlgebra[IO]
// Конфиг фиксирован в конструкторе.
// Вся логика — чистые вычисления, завёрнутые в IO.
// -----------------------------------------------------------------------------
class IOConfigInterpreter(cfg: VendingConfig) extends ConfigAlgebra[IO]:

  def priceOf(product: String): IO[Result[Int]] =
    IO(() => cfg.prices.get(product).toRight(s"Товар '$product' не найден в прайсе"))

  def canAcceptCoin(coin: Int): IO[Boolean] =
    IO(() => cfg.acceptedCoins.contains(coin))

  def effectivePrice(product: String, hour: Int): IO[Result[Int]] =
    IO(() =>
      cfg.prices.get(product)
        .toRight(s"Товар '$product' не найден в прайсе")
        .map(base => (base * cfg.discountRule(product, hour)).toInt)
    )

  def calculateChange(inserted: Int, price: Int): IO[Result[Change]] =
    IO(() =>
      val diff = inserted - price
      if diff < 0 then Left(s"Недостаточно средств: внесено $inserted коп., цена $price коп.")
      else if diff == 0 then Right(List.empty)
      else
        val sorted = cfg.acceptedCoins.toList.sorted.reverse
        def go(rem: Int, coins: List[Int], acc: Change): Result[Change] =
          if rem == 0 then Right(acc.reverse)
          else coins match
            case Nil    => Left(s"Невозможно выдать сдачу $rem коп.")
            case c :: rest =>
              if c <= rem then go(rem - c, coins, c :: acc)
              else go(rem, rest, acc)
        go(diff, sorted, List.empty)
    )

  def canInsertMore(current: Int, coin: Int): IO[Boolean] =
    IO(() => current + coin <= cfg.maxInsertable)

  def availableProducts: IO[List[(String, Int)]] =
    IO(() => cfg.prices.toList.sortBy(_._1))


// -----------------------------------------------------------------------------
// IOLogInterpreter — LogAlgebra[IO]
// Лог хранится в изменяемом буфере внутри класса, обёрнутом в IO.
// getLogs / clearLogs позволяют читать и сбрасывать лог между операциями.
// -----------------------------------------------------------------------------
class IOLogInterpreter extends LogAlgebra[IO]:

  private var buffer: Vector[String] = Vector.empty

  private def formatCoins(amount: Int): String =
    f"${amount / 100}%d.${amount % 100}%02d руб."

  private def append(msg: String): IO[Unit] = IO(() => buffer = buffer :+ msg)

  def log(message: String): IO[Unit] = append(message)

  def explainInsert(coin: Int, accepted: Boolean, total: Int): IO[Unit] =
    if accepted then append(s"[МОНЕТА ] +${formatCoins(coin)}. Итого внесено: ${formatCoins(total)}")
    else append(s"[МОНЕТА ] ${formatCoins(coin)} — номинал не принимается.")

  def explainPrice(product: String, base: Int, final_ : Int, hour: Int): IO[Unit] =
    if final_ < base then
      append(s"[ЦЕНА   ] '$product': ${formatCoins(base)} → ${formatCoins(final_)} (скидка, $hour:00)")
    else
      append(s"[ЦЕНА   ] '$product': ${formatCoins(final_)}")

  def explainPurchase(
                       product:      String,
                       inserted:     Int,
                       price:        Int,
                       changeResult: Result[Change]
                     ): IO[Unit] =
    changeResult match
      case Right(coins) if coins.isEmpty =>
        append(s"[УСПЕХ  ] '$product' куплен за ${formatCoins(price)}. Сдача не требуется.")
      case Right(coins) =>
        val changeStr = coins.map(formatCoins).mkString(", ")
        append(s"[УСПЕХ  ] '$product' куплен за ${formatCoins(price)}. Сдача: $changeStr")
      case Left(reason) =>
        append(s"[ОТКАЗ  ] Покупка '$product' невозможна: $reason.")

  def explainFailure(reason: String): IO[Unit] =
    append(s"[ОШИБКА ] $reason.")

  def explainCancel(returned: Int): IO[Unit] =
    append(s"[ОТМЕНА ] Возврат: ${formatCoins(returned)}.")

  def explainRefill(product: String, amount: Int): IO[Unit] =
    append(s"[СКЛАД  ] '$product' пополнен на $amount шт.")

  def getLogs: IO[Vector[String]]  = IO(() => buffer)
  def clearLogs: IO[Unit]          = IO(() => buffer = Vector.empty)


// -----------------------------------------------------------------------------
// IOMachineInterpreter — MachineAlgebra[IO]
// Состояние автомата хранится в var внутри класса.
// State-переходы запускаются внутри IO — var обновляется там же.
// Конфигурация фиксирована в конструкторе.
// -----------------------------------------------------------------------------
class IOMachineInterpreter(cfg: VendingConfig) extends MachineAlgebra[IO]:

  private var state: MachineState = MachineState.initial

  // Запустить State-переход и сохранить новое состояние.
  private def runState[A](t: State[MachineState, A]): IO[A] =
    IO(() =>
      val (result, newState) = t.run(state)
      state = newState
      result
    )

  // Набрать сдачу из реальных монет кассы (жадный алгоритм).
  private def changeFromCashBox(amount: Int, cashBox: Map[Int, Int]): Result[Change] =
    if amount < 0 then Left(s"Недостаточно средств: не хватает ${-amount} коп.")
    else if amount == 0 then Right(List.empty)
    else
      val available = cashBox.toList.filter(_._2 > 0).sortBy(-_._1)
      def go(rem: Int, coins: List[(Int, Int)], acc: Change): Result[Change] =
        if rem == 0 then Right(acc.reverse)
        else coins match
          case Nil => Left(s"Недостаточно монет в кассе для сдачи $rem коп.")
          case (d, c) :: rest =>
            if d > rem then go(rem, rest, acc)
            else
              val take = (rem / d) min c
              go(rem - take * d, rest, List.fill(take)(d) ::: acc)
      go(amount, available, List.empty)

  def insertCoin(coin: Int): IO[Result[Int]] =
    runState(
      State.get[MachineState].flatMap(s =>
        if !cfg.acceptedCoins.contains(coin) then
          State.pure(Left(s"Монета $coin коп. не принимается"))
        else if s.inserted + coin > cfg.maxInsertable then
          State.pure(Left("Лимит внесения превышен"))
        else
          val newState = s.copy(
            inserted = s.inserted + coin,
            cashBox  = s.cashBox.updated(coin, s.cashBox.getOrElse(coin, 0) + 1)
          )
          State.put(newState).flatMap(_ => State.pure(Right(newState.inserted)))
      )
    )

  def selectProduct(product: String, hour: Int): IO[Result[Change]] =
    runState(
      State.get[MachineState].flatMap(s =>
        val stockCount = s.stock.getOrElse(product, 0)
        if stockCount <= 0 then
          State.pure(Left(s"Товар '$product' отсутствует"))
        else
          val priceResult = cfg.prices.get(product)
            .toRight(s"Товар '$product' не найден в прайсе")
            .map(base => (base * cfg.discountRule(product, hour)).toInt)
          priceResult match
            case Left(err) => State.pure(Left(err))
            case Right(price) =>
              changeFromCashBox(s.inserted - price, s.cashBox) match
                case Left(err) => State.pure(Left(err))
                case Right(coins) =>
                  val newCashBox = coins.foldLeft(s.cashBox) { (box, c) =>
                    val n = box.getOrElse(c, 0)
                    if n > 0 then box.updated(c, n - 1) else box
                  }
                  val newState = s.copy(
                    stock    = s.stock.updated(product, stockCount - 1),
                    inserted = 0,
                    revenue  = s.revenue + price,
                    cashBox  = newCashBox
                  )
                  State.put(newState).flatMap(_ => State.pure(Right(coins)))
      )
    )

  def cancelPurchase: IO[Result[Int]] =
    runState(
      State.get[MachineState].flatMap(s =>
        if s.inserted == 0 then
          State.pure(Left("Нет внесённых средств для возврата"))
        else
          val returned = s.inserted
          val coins    = changeFromCashBox(returned, s.cashBox).getOrElse(List.empty)
          val newBox   = coins.foldLeft(s.cashBox) { (box, c) =>
            val n = box.getOrElse(c, 0)
            if n > 0 then box.updated(c, n - 1) else box
          }
          State.put(s.copy(inserted = 0, cashBox = newBox))
            .flatMap(_ => State.pure(Right(returned)))
      )
    )

  def refillProduct(product: String, amount: Int): IO[Result[Int]] =
    runState(
      State.get[MachineState].flatMap(s =>
        if amount <= 0 then
          State.pure(Left("Количество пополнения должно быть > 0"))
        else if !cfg.prices.contains(product) then
          State.pure(Left(s"Товар '$product' неизвестен"))
        else
          val newStock = s.stock.getOrElse(product, 0) + amount
          State.put(s.copy(stock = s.stock.updated(product, newStock)))
            .flatMap(_ => State.pure(Right(newStock)))
      )
    )

  def getState: IO[MachineState] = IO(() => state)


// -----------------------------------------------------------------------------
// IOConsoleInterpreter — ConsoleAlgebra[IO]
// Тонкая обёртка над стандартными IO-примитивами из Monads.scala.
// -----------------------------------------------------------------------------
class IOConsoleInterpreter extends ConsoleAlgebra[IO]:
  def putStrLn(s: String): IO[Unit] = IO.putStrLn(s)
  def putStr(s: String): IO[Unit]   = IO.putStr(s)
  def readLine: IO[String]          = IO.readLine