

object Display:

  val separator: String = "─" * 50

  def formatCoins(amount: Int): String =
    f"${amount / 100}%d.${amount % 100}%02d руб."

  // Меню товаров. Цена со скидкой вычисляется через cfg напрямую -
  // это чистое вычисление, не требует F.
  def renderMenu(products: List[(String, Int)], hour: Int, cfg: VendingConfig): String =
    val rows = products.map { (name, base) =>
      val final_ = (base * cfg.discountRule(name, hour)).toInt
      if final_ < base then
        f"  %%-10s  ${formatCoins(base)}%s - ${formatCoins(final_)}%s  !!!".format(name, "", "")
      else
        f"  %%-10s  ${formatCoins(final_)}%s".format(name, "")
    }
    (List(separator, "  МЕНЮ АВТОМАТА", separator) ++ rows ++ List(separator))
      .mkString("\n")

  def renderState(s: MachineState): String =
    val stockLines = s.stock.toList.sortBy(_._1)
      .map((name, n) => f"  %%-10s  $n%d шт.".format(name))
    val cashLines = s.cashBox.toList.sortBy(-_._1)
      .map((d, n) => f"  ${formatCoins(d)}%s  ×  $n%d")
    (List(separator, "  СОСТОЯНИЕ АВТОМАТА", separator,
      s"  Внесено:  ${formatCoins(s.inserted)}",
      s"  Выручка:  ${formatCoins(s.revenue)}",
      separator, "  Склад:")
      ++ stockLines
      ++ List(separator, "  Касса:")
      ++ cashLines
      ++ List(separator))
      .mkString("\n")

  def renderLog(log: Vector[String]): String =
    if log.isEmpty then ""
    else (List(separator, "  ЛОГ ОПЕРАЦИИ") ++ log.toList ++ List(separator))
      .mkString("\n")

  val banner: String =
    s"""
       |$separator
       |     ТОРГОВЫЙ АВТОМАТ
       |$separator
       |  Команды:
       |    buy      - купить товар
       |    insert   - внести монету
       |    cancel   - отменить и вернуть деньги
       |    refill   - пополнить склад (сервисный режим)
       |    status   - показать состояние автомата
       |    menu     - показать меню товаров
       |    quit     - выйти
       |$separator""".stripMargin


// -------------------------------------
// 2. SCENARIO STEP
// -----------------------------------------------------------------------------
// Все методы принимают console: ConsoleAlgebra[F] и возвращают F[_].
// Никакого IO внутри - только вызовы методов алгебры.

object ScenarioStep:

  def showBanner[F[_]: Monad](console: ConsoleAlgebra[F]): F[Unit] =
    console.putStrLn(Display.banner)

  def showMenu[F[_]: Monad](
                             cfg:     VendingConfig,
                             hour:    Int,
                             config:  ConfigAlgebra[F],
                             console: ConsoleAlgebra[F]
                           ): F[Unit] =
    for
      products <- config.availableProducts
      _        <- console.putStrLn(Display.renderMenu(products, hour, cfg))
    yield ()

  def showState[F[_]: Monad](
                              machine: MachineAlgebra[F],
                              console: ConsoleAlgebra[F]
                            ): F[Unit] =
    for
      s <- machine.getState
      _ <- console.putStrLn(Display.renderState(s))
    yield ()

  def showLog[F[_]: Monad](
                            logger:  LogAlgebra[F],
                            console: ConsoleAlgebra[F]
                          ): F[Unit] =
    for
      log <- logger.getLogs
      _   <- if log.isEmpty then summon[Monad[F]].pure(())
      else console.putStrLn(Display.renderLog(log))
      _   <- logger.clearLogs
    yield ()

  // prompt: напечатать приглашение, прочитать строку, привести к нижнему регистру.
  def prompt[F[_]: Monad](msg: String, console: ConsoleAlgebra[F]): F[String] =
    for
      _ <- console.putStr(s"\n$msg > ")
      s <- console.readLine
    yield s.trim.toLowerCase

  // promptRaw: то же, без toLowerCase - для названий товаров нужно.
  def promptRaw[F[_]: Monad](msg: String, console: ConsoleAlgebra[F]): F[String] =
    for
      _ <- console.putStr(s"\n$msg > ")
      s <- console.readLine
    yield s.trim

  // readInt: читать строку до тех пор, пока не введут целое число.
  def readInt[F[_]: Monad](msg: String, console: ConsoleAlgebra[F]): F[Int] =
    for
      s <- promptRaw(msg, console)
      result <- s.toIntOption match
        case Some(n) => summon[Monad[F]].pure(n)
        case None    =>
          for
            _ <- console.putStrLn(s"  Ошибка: '$s' - не число. Попробуйте снова.")
            n <- readInt(msg, console)
          yield n
    yield result

  val getCurrentHour: IO[Int] =
    IO(() => java.time.LocalTime.now().getHour)


// -----------------------------------------------------------------------------
//  SCENARIO LOOP - главный цикл, параметризованный по F
// Принимает все четыре алгебры и еще конфигурацию.
// Не знает ничего вообще про IO, State, Reader, Writer - только про F[_]: Monad.

object ScenarioLoop:

  def handleInsert[F[_]: Monad](
                                 machine: MachineAlgebra[F],
                                 logger:  LogAlgebra[F],
                                 console: ConsoleAlgebra[F]
                               ): F[Unit] =
    for
      coin   <- ScenarioStep.readInt("Номинал монеты (копейки)", console)
      result <- machine.insertCoin(coin)
      _      <- result match
        case Right(total) =>
          logger.explainInsert(coin, accepted = true, total)
        case Left(err) =>
          logger.explainInsert(coin, accepted = false, 0)
      _      <- ScenarioStep.showLog(logger, console)
      _      <- result match
        case Right(total) =>
          console.putStrLn(s"  Итого внесено: ${Display.formatCoins(total)}")
        case Left(err) =>
          console.putStrLn(s"  Отказ: $err")
    yield ()

  def handleBuy[F[_]: Monad](
                              machine: MachineAlgebra[F],
                              config:  ConfigAlgebra[F],
                              logger:  LogAlgebra[F],
                              console: ConsoleAlgebra[F],
                              cfg:     VendingConfig,
                              hour:    Int
                            ): F[Unit] =
    for
      _       <- ScenarioStep.showMenu(cfg, hour, config, console)
      product <- ScenarioStep.promptRaw("Название товара", console)
      // Логируем цену до покупки
      price   <- config.effectivePrice(product, hour)
      _       <- price match
        case Right(p) =>
          config.priceOf(product).flatMap(base =>
            logger.explainPrice(product, base.getOrElse(p), p, hour)
          )
        case Left(_) => summon[Monad[F]].pure(())
      result  <- machine.selectProduct(product, hour)
      _       <- logger.explainPurchase(
        product,
        0,  // inserted до покупки неизвестен здесь - логируем в интерпретаторе
        price.getOrElse(0),
        result
      )
      _       <- ScenarioStep.showLog(logger, console)
      _       <- result match
        case Right(change) if change.isEmpty =>
          console.putStrLn("  Покупка успешна. Сдача не требуется.")
        case Right(change) =>
          console.putStrLn(
            s"  Покупка успешна. Сдача: ${change.map(Display.formatCoins).mkString(", ")}"
          )
        case Left(err) =>
          console.putStrLn(s"  Покупка отклонена: $err")
    yield ()

  def handleCancel[F[_]: Monad](
                                 machine: MachineAlgebra[F],
                                 logger:  LogAlgebra[F],
                                 console: ConsoleAlgebra[F]
                               ): F[Unit] =
    for
      result <- machine.cancelPurchase
      _      <- result match
        case Right(returned) => logger.explainCancel(returned)
        case Left(err)       => logger.explainFailure(err)
      _      <- ScenarioStep.showLog(logger, console)
      _      <- result match
        case Right(returned) =>
          console.putStrLn(s"  Возвращено: ${Display.formatCoins(returned)}")
        case Left(err) =>
          console.putStrLn(s"  $err")
    yield ()

  def handleRefill[F[_]: Monad](
                                 machine: MachineAlgebra[F],
                                 logger:  LogAlgebra[F],
                                 console: ConsoleAlgebra[F]
                               ): F[Unit] =
    for
      product <- ScenarioStep.promptRaw("Название товара", console)
      amount  <- ScenarioStep.readInt("Количество единиц", console)
      result  <- machine.refillProduct(product, amount)
      _       <- result match
        case Right(_)  => logger.explainRefill(product, amount)
        case Left(err) => logger.explainFailure(err)
      _       <- ScenarioStep.showLog(logger, console)
      _       <- result match
        case Right(stock) =>
          console.putStrLn(s"  Новый остаток '$product': $stock шт.")
        case Left(err) =>
          console.putStrLn(s"  Ошибка: $err")
    yield ()

  // loop: рекурсивный цикл команд через Map сделали.
  // Конкретный F подставляется только в Main.
  def loop[F[_]: Monad](
                         machine: MachineAlgebra[F],
                         config:  ConfigAlgebra[F],
                         logger:  LogAlgebra[F],
                         console: ConsoleAlgebra[F],
                         cfg:     VendingConfig,
                         hour:    Int
                       ): F[Unit] =

    val commands: Map[String, F[Unit]] = Map(
      "buy"    -> handleBuy(machine, config, logger, console, cfg, hour),
      "insert" -> handleInsert(machine, logger, console),
      "cancel" -> handleCancel(machine, logger, console),
      "refill" -> handleRefill(machine, logger, console),
      "status" -> ScenarioStep.showState(machine, console),
      "menu"   -> ScenarioStep.showMenu(cfg, hour, config, console),
      "quit"   -> (for
        _ <- console.putStrLn("\n  Завершение работы.")
        _ <- ScenarioStep.showState(machine, console)
      yield ())
    )

    for
      cmd <- ScenarioStep.prompt("Команда", console)
      action  = commands.get(cmd).getOrElse(
        console.putStrLn(
          s"  Неизвестная команда: '$cmd'. " +
            s"Доступные: ${commands.keys.mkString(", ")}."
        )
      )
      _      <- action
      _      <- if cmd == "quit" then summon[Monad[F]].pure(())
      else loop(machine, config, logger, console, cfg, hour)
    yield ()


// -----------------------------------------------------------------------------

// MAIN

// Здесь F будем фиксировать как IO, но можно
// будет также сделать другие интерпретаторы и подставить (условный id для тестов)...
// Создаем четыре IO-интерпретатора и передаём их в ScenarioLoop.loop.
// unsafeRun вызывается ровно один раз.

object Main extends App:

  val cfg     = DefaultConfig.config
  val machine = new IOMachineInterpreter(cfg)
  val config  = new IOConfigInterpreter(cfg)
  val logger  = new IOLogInterpreter
  val console = new IOConsoleInterpreter

  val program: IO[Unit] =
    for
      _ <- ScenarioStep.showBanner(console)
      hour <- ScenarioStep.getCurrentHour
      _ <- ScenarioStep.showMenu(cfg, hour, config, console)
      _ <- ScenarioLoop.loop(machine, config, logger, console, cfg, hour)
    yield ()

  program.unsafeRun()