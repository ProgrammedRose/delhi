// =============================================================================
// ЛР2: Интерпретаторы - конкретные реализации алгебр
// =============================================================================
// Два набора интерпретаторов:
//
//   ПРОДАКШН (Live*):
//     LiveConfigInterpreter  - через Reader[VendingConfig, _]  (как в ЛР1 Блок 1)
//     LiveLogInterpreter     - через Writer[Vector[String], _] (как в ЛР1 Блок 2)
//     LiveMachineInterpreter - через State[MachineState, _]    (как в ЛР1 Блок 3)
//
//   ТЕСТ (Test*):
//     TestConfigInterpreter  - через Id[_] (чистая функция, без обёртки)
//     TestLogInterpreter     - через Id[_], лог в изменяемом буфере
//     TestMachineInterpreter - через Id[_], состояние в изменяемом буфере
//
// Id[A] = A - тождественный контейнер, «нет никакого эффекта».
// Позволяет вызывать методы алгебр как обычные функции без монадического
// контекста - удобно для тестов и демонстрации.
// =============================================================================


// =============================================================================
// ВСПОМОГАТЕЛЬНЫЙ ТИП: Id[A]
// =============================================================================
// Id - тождественная монада: «контейнер», который ничего не добавляет.
// Id[A] - это просто A. Нужен чтобы TestInterpreter-ы реализовывали
// те же алгебры что и Live-версии, только без монадических эффектов.

type Id[A] = A

// given-экземпляр Monad для Id - нужен чтобы программа Program.scala
// могла использовать for-comprehension с Test-интерпретаторами.
given idMonad: Monad[Id] with
  def pure[A](a: A): Id[A]                       = a
  def flatMap[A, B](ma: Id[A])(f: A => Id[B]): Id[B] = f(ma)


// =============================================================================
// ПРОДАКШН-ИНТЕРПРЕТАТОРЫ (Live*)
// =============================================================================
// Каждый Live-интерпретатор делает ровно то, что делали функции из ЛР1,
// только теперь завёрнутые в алгебру.


// -----------------------------------------------------------------------------
// LiveConfigInterpreter
// Реализует ConfigAlgebra через Reader[VendingConfig, _].
// Делегирует всё в VendingReader из Domain.scala.
// F фиксирован как [A] =>> Reader[VendingConfig, A].
// -----------------------------------------------------------------------------
class LiveConfigInterpreter
  extends ConfigAlgebra[[A] =>> Reader[VendingConfig, A]]:

  def priceOf(product: String): Reader[VendingConfig, Result[Int]] =
    VendingReader.priceOf(product)

  def canAcceptCoin(coin: Int): Reader[VendingConfig, Boolean] =
    VendingReader.canAcceptCoin(coin)

  def effectivePrice(product: String, hour: Int): Reader[VendingConfig, Result[Int]] =
    VendingReader.effectivePrice(product, hour)

  def calculateChange(inserted: Int, price: Int): Reader[VendingConfig, Result[Change]] =
    VendingReader.calculateChange(inserted, price)

  def canInsertMore(currentInserted: Int, coin: Int): Reader[VendingConfig, Boolean] =
    VendingReader.canInsertMore(currentInserted, coin)

  def availableProducts: Reader[VendingConfig, List[(String, Int)]] =
    VendingReader.availableProducts


// -----------------------------------------------------------------------------
// LiveLogInterpreter
// Реализует LogAlgebra через Writer[Vector[String], _].
// Делегирует форматирование в VendingWriter из Domain.scala.
// F фиксирован как [A] =>> Writer[Vector[String], A].
//
// Особенность: getLogs и clearLogs не очень естественны для Writer -
// Writer накапливает лог внутри цепочки flatMap, а не в изменяемом буфере.
// Поэтому getLogs возвращает Writer с пустым значением и пустым логом
// (лог читается снаружи через .run после завершения цепочки),
// а clearLogs - просто Writer с Unit и пустым логом.
// Это честно: в продакшне лог извлекается в конце через writerResult.run.
// -----------------------------------------------------------------------------
class LiveLogInterpreter
  extends LogAlgebra[[A] =>> Writer[Vector[String], A]]:

  // Псевдоним для краткости
  private type W[A] = Writer[Vector[String], A]

  def log(message: String): W[Unit] =
    Writer.log(message)

  def explainInsert(coin: Int, accepted: Boolean, total: Int): W[Unit] =
    VendingWriter.explainInsert(coin, accepted, total)

  def explainPrice(product: String, base: Int, final_ : Int, hour: Int): W[Unit] =
    // explainPrice в VendingWriter возвращает W[Int] (цену как значение).
    // Нам нужен W[Unit] - преобразуем через map.
    VendingWriter.explainPrice(product, base, final_, hour).map(_ => ())

  def explainPurchase(
                       product:      String,
                       inserted:     Int,
                       price:        Int,
                       changeResult: Result[Change]
                     ): W[Unit] =
    VendingWriter.explainPurchase(product, inserted, price, changeResult).map(_ => ())

  def explainFailure(reason: String): W[Unit] =
    VendingWriter.explainFailure(reason)

  def explainCancel(returned: Int): W[Unit] =
    VendingWriter.explainCancel(returned)

  def explainRefill(product: String, amount: Int): W[Unit] =
    VendingWriter.explainRefill(product, amount)

  // В Writer нет изменяемого буфера - лог живёт внутри цепочки.
  // getLogs возвращает Writer с пустым логом: реальный лог читается
  // через .run на финальном Writer-значении снаружи цепочки.
  def getLogs: W[Vector[String]] =
    Writer((Vector.empty[String], Vector.empty[String]))

  // clearLogs в Writer-интерпретаторе ничего не делает -
  // каждая новая цепочка flatMap начинает с чистого лога автоматически.
  def clearLogs: W[Unit] =
    Writer.log("")   // пустая запись - нет эффекта на лог по смыслу
    Writer(((), Vector.empty[String]))


// -----------------------------------------------------------------------------
// LiveMachineInterpreter
// Реализует MachineAlgebra через State[MachineState, _].
// Делегирует переходы в VendingMachine из Domain.scala.
// F фиксирован как [A] =>> State[MachineState, Result[A]].
//
// Отличие от ЛР1: Writer убран из возвращаемого типа - лог теперь
// в ответственности LogAlgebra. Переходы возвращают State[MachineState, Result[A]].
// Внутренняя логика (calculateChangeFromCashBox и пр.) та же что в ЛР1.
// -----------------------------------------------------------------------------
class LiveMachineInterpreter(cfg: VendingConfig)
  extends MachineAlgebra[[A] =>> State[MachineState, A]]:

  // Вспомогательный метод - копия из VendingMachine (там приватная).
  // Набрать сдачу из реальных монет кассы.
  private def changeFromCashBox(amount: Int, cashBox: Map[Int, Int]): Result[Change] =
    if amount < 0 then Left(s"Недостаточно средств: не хватает ${-amount} коп.")
    else if amount == 0 then Right(List.empty)
    else
      val available = cashBox.toList.filter(_._2 > 0).sortBy(-_._1)
      def go(rem: Int, coins: List[(Int, Int)], acc: Change): Result[Change] =
        if rem == 0 then Right(acc.reverse)
        else coins match
          case Nil => Left(s"Недостаточно монет в кассе для сдачи $rem коп.")
          case (denom, count) :: rest =>
            if denom > rem then go(rem, rest, acc)
            else
              val take = (rem / denom) min count
              go(rem - take * denom, rest, List.fill(take)(denom) ::: acc)
      go(amount, available, List.empty)

  def insertCoin(coin: Int): State[MachineState, Result[Int]] =
    State.get[MachineState].flatMap(s =>
      val accepted    = VendingReader.canAcceptCoin(coin).run(cfg)
      val withinLimit = VendingReader.canInsertMore(s.inserted, coin).run(cfg)
      if !accepted then
        State.pure(Left(s"Монета $coin коп. не принимается"))
      else if !withinLimit then
        State.pure(Left(s"Лимит внесения превышен"))
      else
        val newState = s.copy(
          inserted = s.inserted + coin,
          cashBox  = s.cashBox.updated(coin, s.cashBox.getOrElse(coin, 0) + 1)
        )
        State.put(newState).flatMap(_ => State.pure(Right(newState.inserted)))
    )

  def selectProduct(product: String, hour: Int): State[MachineState, Result[Change]] =
    State.get[MachineState].flatMap(s =>
      val stockCount = s.stock.getOrElse(product, 0)
      if stockCount <= 0 then
        State.pure(Left(s"Товар '$product' отсутствует"))
      else
        VendingReader.effectivePrice(product, hour).run(cfg) match
          case Left(err) =>
            State.pure(Left(err))
          case Right(price) =>
            changeFromCashBox(s.inserted - price, s.cashBox) match
              case Left(err) =>
                State.pure(Left(err))
              case Right(changeCoins) =>
                val newCashBox = changeCoins.foldLeft(s.cashBox) { (box, c) =>
                  val n = box.getOrElse(c, 0)
                  if n > 0 then box.updated(c, n - 1) else box
                }
                val newState = s.copy(
                  stock    = s.stock.updated(product, stockCount - 1),
                  inserted = 0,
                  revenue  = s.revenue + price,
                  cashBox  = newCashBox
                )
                State.put(newState).flatMap(_ => State.pure(Right(changeCoins)))
    )

  def cancelPurchase: State[MachineState, Result[Int]] =
    State.get[MachineState].flatMap(s =>
      if s.inserted == 0 then
        State.pure(Left("Нет внесённых средств для возврата"))
      else
        val returned = s.inserted
        val changeCoins = changeFromCashBox(returned, s.cashBox).getOrElse(List.empty)
        val newCashBox  = changeCoins.foldLeft(s.cashBox) { (box, c) =>
          val n = box.getOrElse(c, 0)
          if n > 0 then box.updated(c, n - 1) else box
        }
        State.put(s.copy(inserted = 0, cashBox = newCashBox))
          .flatMap(_ => State.pure(Right(returned)))
    )

  def refillProduct(product: String, amount: Int): State[MachineState, Result[Int]] =
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

  /*def getState: State[MachineState, Result[MachineState]] =
    State.get[MachineState].flatMap(s => State.pure(Right(s)))*/
  def getState: State[MachineState, MachineState] =
    State.get[MachineState]


// =============================================================================
// ТЕСТ-ИНТЕРПРЕТАТОРЫ (Test*)
// =============================================================================
// Используют Id[A] = A - никаких монадических обёрток.
// Методы выглядят и вызываются как обычные функции.
// Состояние и лог хранятся в var внутри класса (изолированно).
// Нужны для демонстрации и простой проверки логики без IO.


// -----------------------------------------------------------------------------
// TestConfigInterpreter
// ConfigAlgebra[Id] - чистые функции, конфиг фиксирован в конструкторе.
// -----------------------------------------------------------------------------
class TestConfigInterpreter(cfg: VendingConfig) extends ConfigAlgebra[Id]:

  def priceOf(product: String): Result[Int] =
    cfg.prices.get(product).toRight(s"Товар '$product' не найден")

  def canAcceptCoin(coin: Int): Boolean =
    cfg.acceptedCoins.contains(coin)

  def effectivePrice(product: String, hour: Int): Result[Int] =
    priceOf(product).map(p => (p * cfg.discountRule(product, hour)).toInt)

  def calculateChange(inserted: Int, price: Int): Result[Change] =
    VendingReader.calculateChange(inserted, price).run(cfg)

  def canInsertMore(currentInserted: Int, coin: Int): Boolean =
    currentInserted + coin <= cfg.maxInsertable

  def availableProducts: List[(String, Int)] =
    cfg.prices.toList.sortBy(_._1)


// -----------------------------------------------------------------------------
// TestLogInterpreter
// LogAlgebra[Id] - пишет в изменяемый внутренний буфер.
// Позволяет после вызовов проверить что именно было залогировано.
// -----------------------------------------------------------------------------
class TestLogInterpreter extends LogAlgebra[Id]:

  // Изменяемый буфер - изолирован внутри класса.
  private var buffer: Vector[String] = Vector.empty

  private def append(msg: String): Unit = buffer = buffer :+ msg

  def log(message: String): Unit                               = append(message)
  def explainFailure(reason: String): Unit                     = append(s"[ОШИБКА ] $reason.")
  def explainCancel(returned: Int): Unit                       = append(s"[ОТМЕНА ] Возврат: ${returned} коп.")
  def explainRefill(product: String, amount: Int): Unit        = append(s"[СКЛАД  ] '$product' +$amount шт.")

  def explainInsert(coin: Int, accepted: Boolean, total: Int): Unit =
    if accepted then append(s"[МОНЕТА ] +$coin коп. Итого: $total коп.")
    else append(s"[МОНЕТА ] $coin коп. отклонена.")

  def explainPrice(product: String, base: Int, final_ : Int, hour: Int): Unit =
    if final_ < base then
      append(s"[ЦЕНА   ] '$product': $base - $final_ коп. (скидка в $hour:00)")
    else
      append(s"[ЦЕНА   ] '$product': $final_ коп.")

  def explainPurchase(
                       product:      String,
                       inserted:     Int,
                       price:        Int,
                       changeResult: Result[Change]
                     ): Unit =
    changeResult match
      case Right(coins) => append(s"[УСПЕХ] '$product' куплен. Сдача: ${coins.sum} коп.")
      case Left(reason) => append(s"[ОТКАЗ] '$product': $reason.")

  def getLogs: Vector[String]  = buffer
  def clearLogs: Unit          = buffer = Vector.empty


// ----------------------------------------
// TestMachineInterpreter
// MachineAlgebra[Id] - состояние в изменяемом буфере.
// Все методы выполняются немедленно и возвращают Result[A] напрямую.
// -----------------------------------------------------------------------------
class TestMachineInterpreter(cfg: VendingConfig, initial: MachineState)
  extends MachineAlgebra[Id]:

  // Изменяемое состояние - изолировано внутри класса.
  private var state: MachineState = initial

  private def changeFromCashBox(amount: Int, cashBox: Map[Int, Int]): Result[Change] =
    if amount < 0 then Left(s"Недостаточно средств: не хватает ${-amount} коп.")
    else if amount == 0 then Right(List.empty)
    else
      val available = cashBox.toList.filter(_._2 > 0).sortBy(-_._1)
      def go(rem: Int, coins: List[(Int, Int)], acc: Change): Result[Change] =
        if rem == 0 then Right(acc.reverse)
        else coins match
          case Nil => Left(s"Недостаточно монет для сдачи $rem коп.")
          case (d, c) :: rest =>
            if d > rem then go(rem, rest, acc)
            else
              val take = (rem / d) min c
              go(rem - take * d, rest, List.fill(take)(d) ::: acc)
      go(amount, available, List.empty)

  def insertCoin(coin: Int): Result[Int] =
    if !cfg.acceptedCoins.contains(coin) then
      Left(s"Монета $coin коп. не принимается")
    else if state.inserted + coin > cfg.maxInsertable then
      Left("Лимит внесения превышен")
    else
      state = state.copy(
        inserted = state.inserted + coin,
        cashBox  = state.cashBox.updated(coin, state.cashBox.getOrElse(coin, 0) + 1)
      )
      Right(state.inserted)

  def selectProduct(product: String, hour: Int): Result[Change] =
    val stockCount = state.stock.getOrElse(product, 0)
    if stockCount <= 0 then Left(s"Товар '$product' отсутствует")
    else
      val priceResult = VendingReader.effectivePrice(product, hour).run(cfg)
      priceResult.flatMap(price =>
        changeFromCashBox(state.inserted - price, state.cashBox).map(coins =>
          val newCashBox = coins.foldLeft(state.cashBox) { (box, c) =>
            val n = box.getOrElse(c, 0)
            if n > 0 then box.updated(c, n - 1) else box
          }
          state = state.copy(
            stock    = state.stock.updated(product, stockCount - 1),
            inserted = 0,
            revenue  = state.revenue + price,
            cashBox  = newCashBox
          )
          coins
        )
      )

  def cancelPurchase: Result[Int] =
    if state.inserted == 0 then Left("Нет внесённых средств")
    else
      val returned    = state.inserted
      val changeCoins = changeFromCashBox(returned, state.cashBox).getOrElse(List.empty)
      val newCashBox  = changeCoins.foldLeft(state.cashBox) { (box, c) =>
        val n = box.getOrElse(c, 0)
        if n > 0 then box.updated(c, n - 1) else box
      }
      state = state.copy(inserted = 0, cashBox = newCashBox)
      Right(returned)

  def refillProduct(product: String, amount: Int): Result[Int] =
    if amount <= 0 then Left("Количество должно быть > 0")
    else if !cfg.prices.contains(product) then Left(s"Товар '$product' неизвестен")
    else
      val newStock = state.stock.getOrElse(product, 0) + amount
      state = state.copy(stock = state.stock.updated(product, newStock))
      Right(newStock)

  /*def getState: Result[MachineState] = Right(state)*/
  def getState: MachineState = state