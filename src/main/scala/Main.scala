
//   Всё что здесь есть — это ОПИСАНИЕ взаимодействия, а не само взаимодействие.
//   Каждая функция возвращает IO[A].
//   Реальные грязные эффекты происходят только в одном месте -
//   в самом конце, когда вызывается unsafeRun().
//
//


// ------------------------
// 1. DISPLAY — форматирование состояния для вывода
// ------------------------
// Чистые функции: принимают данные, возвращают строки.
// Никакого IO здесь нету - только данные в текст.

object Display:
  
  val separator: String = "─" * 50


  // Дублируется из VendingWriter (там приватная) — здесь нужна для вывода состояния.
  def formatCoins(amount: Int): String =
    f"${amount / 100}%d.${amount % 100}%02d руб."

  // renderMenu: отформатировать список товаров с ценами в виде меню.
  // Принимает список (название, базовая Цена) и текущий час для отображения скидок.
  // Возвращает многострочную строку - готовую для println.
  def renderMenu(products: List[(String, Int)], hour: Int, cfg: VendingConfig): String =
    val header = List(separator, "  МЕНЮ АВТОМАТА", separator)

    val rows = products.map { (name, basePrice) =>
      // Для каждого товара считаем актуальную цену с учётом скидки.
      val finalPrice = VendingReader.effectivePrice(name, hour).run(cfg)
      finalPrice match
        case Right(price) if price < basePrice =>
          // Скидка активна - показываем обе цены.
          f"  %%-10s  ${formatCoins(basePrice)}%s - ${formatCoins(price)}%s  !!!".format(name, "", "")
        case Right(price) =>
          f"  %%-10s  ${formatCoins(price)}%s".format(name, "")
        case Left(_) =>
          f"  %%-10s  цена недоступна".format(name)
    }

    val footer = List(separator)
    (header ++ rows ++ footer).mkString("\n")

  // renderState: отформатировать полное состояние автомата.
  // Используется в конце сценария и при команде «статус».
  def renderState(s: MachineState): String =
    val stockLines = s.stock.toList.sortBy(_._1).map { (name, count) =>
      f"  %%-10s  $count%d шт.".format(name)
    }

    val cashLines = s.cashBox.toList.sortBy(-_._1).map { (denom, count) =>
      f"  ${formatCoins(denom)}%s  x  $count%d"
    }

    List(
      separator,
      "  СОСТОЯНИЕ АВТОМАТА",
      separator,
      s"  Внесено:  ${formatCoins(s.inserted)}",
      s"  Выручка:  ${formatCoins(s.revenue)}",
      separator,
      "  Склад:",
    )
      .concat(stockLines)
      .concat(List(separator, "  Касса:"))
      .concat(cashLines)
      .concat(List(separator))
      .mkString("\n")

  // renderLog: отформатировать накопленный Writer-лог для вывода.
  // Добавляет заголовок и разделитель - чтобы лог не сливался с остальным выводом.
  def renderLog(log: Vector[String]): String =
    if log.isEmpty then ""
    else
      (List(separator, "  ЛОГ ОПЕРАЦИИ") ++ log.toList ++ List(separator))
        .mkString("\n")


// --------------------------------
// 2. SCENARIO STEP
// --------

//
// Почему не писать всё в одной большой функции?
// Маленькие шаги легко тестировать и переиспользовать.
//

object ScenarioStep:

  // showBanner: напечатать приветственный экран при запуске.
  val showBanner: IO[Unit] =
    IO.putStrLn(
      s"""
         |${Display.separator}
         |     ТОРГОВЫЙ АВТОМАТ 
         |${Display.separator}
         |  Команды:
         |    buy      - купить товар
         |    insert   - внести монету
         |    cancel   - отменить и вернуть деньги
         |    refill   - пополнить склад (сервисный режим)
         |    status   - показать состояние автомата
         |    menu     - показать меню товаров
         |    quit     - выйти
         |${Display.separator}""".stripMargin
    )

  // showMenu: прочитать список товаров через Reader и напечатать меню.
  // Принимает конфигурацию и текущий час.
  def showMenu(cfg: VendingConfig, hour: Int): IO[Unit] =
    // VendingReader.availableProducts — это Reader, запускаем его с cfg.
    val products = VendingReader.availableProducts.run(cfg)
    IO.putStrLn(Display.renderMenu(products, hour, cfg))

  // showState: напечатать текущее состояние автомата.
  def showState(state: MachineState): IO[Unit] =
    IO.putStrLn(Display.renderState(state))

  // showLog: напечатать Writer-лог если он непустой.
  def showLog(log: Vector[String]): IO[Unit] =
    val rendered = Display.renderLog(log)
    if rendered.isEmpty then IO.pure(())
    else IO.putStrLn(rendered)

  
  
  // prompt: напечатать приглашение и прочитать строку.
  // Возвращает IO[String]
  def prompt(msg: String): IO[String] =
    for
      _ <- IO.putStr(s"\n$msg > ")
      s <- IO.readLine
    yield s.trim.toLowerCase

  // promptRaw: то же, но без toLowerCase - для ввода названий товаров.
  def promptRaw(msg: String): IO[String] =
    for
      _ <- IO.putStr(s"\n$msg > ")
      s <- IO.readLine
    yield s.trim

  // readInt: прочитать целое число, повторяя запрос при ошибке ввода.
  // Рекурсия через IO - если ввод некорректен, возвращаем IO который
  // снова читает строку. Хвостовая рекурсия через IO безопасна:
  // реальный вызов происходит только при unsafeRun, стека не накапливается.
  def readInt(msg: String): IO[Int] =
    for
      s <- promptRaw(msg)
      result <- s.toIntOption match
        case Some(n) => IO.pure(n)
        case None    =>
          for
            _ <- IO.putStrLn(s"  Ошибка: '$s' - не число. Попробуйте снова.")
            n <- readInt(msg) // повторяем запрос
          yield n
    yield result

  // getCurrentHour: прочитать текущий час из системных часов.
  // Завёрнуто в IO — это побочный эффект (обращение к системному времени).
  val getCurrentHour: IO[Int] =
    IO(() => java.time.LocalTime.now().getHour)


// -----------------------------------------------------------------------------
// 3. APPSTATE — мост между чистым State и IO-миром
// -------------------------
// State - это чистая монада, она не хранит состояние сама по себе.
// Каждый вызов run(s) возвращает новое состояние, а его нужно где-то хранить.
//
// Поскольку мутации или трансформации являются чем-то страшным и непонятным (и вроде как
// они запрещены в рамках задания к данной лабораторной) - мы используем
// обычный var внутри класса, завёрнутый в IO.
//
// Это единственное место во всём этом проекте где есть var.


class AppState(initial: MachineState):

  // Единственный var в проекте - текущее состояние автомата.
  // Снаружи к нему нет доступа, только через методы ниже.
  private var current: MachineState = initial

  // get: прочитать текущее состояние как IO-действие.
  val get: IO[MachineState] =
    IO(() => current)

  // runTransition: запустить State-переход и обновить текущее состояние.
  // Принимает State[MachineState, W[Result[A]]] - переход с логом и результатом.
  // Возвращает IO[(Result[A], Vector[String])] - результат и лог.
  //
  // Это ключевая «точка соединения» между чистым State-миром и IO-миром:
  //   1. run(current) - запускаем чистый переход со старым состоянием
  //   2. current = newState - сохраняем новое состояние - тут наша единственная мутация.
  //   3. writerResult.run - разворачиваем Writer в (result, log)
  
  
  def runTransition[A](transition: State[MachineState, W[Result[A]]]): IO[(Result[A], Vector[String])] =
    IO(() =>
      val (writerResult, newState) = transition.run(current)
      current = newState                          // обновляем состояние
      val (result, log) = writerResult.run        // разворачиваем Writer
      (result, log)
    )


// ---------------
// 4. SCENARIO LOOP
// ------------
// Сценарий у нас - это рекурсивный IO-цикл:
//   показать приглашение, прочитать команду, выполнить, повторить
//
// Каждая ветка команды - отдельная функция, возвращающая IO[Unit].
// Главный цикл loop возвращает IO[Unit].
//
object ScenarioLoop:
  // handleInsert - это сценарий внесения монеты.
  // Спрашивает номинал, запускает переход insertCoin, печатает лог.
  def handleInsert(appState: AppState, cfg: VendingConfig): IO[Unit] =
    for
      coin <- ScenarioStep.readInt("Номинал монеты (копейки)")
      (result, log) <- appState.runTransition(VendingMachine.insertCoin(coin, cfg))
      _ <- ScenarioStep.showLog(log)
      _ <- result match
        case Right(total) =>
          IO.putStrLn(s"  Итого внесено: ${Display.formatCoins(total)}")
        case Left(err) =>
          IO.putStrLn(s"  Отказ: $err")
    yield ()

  // handleBuy: сценарий покупки товара.
  // Спрашивает название товара, запускает переход selectProduct, печатает лог.
  def handleBuy(appState: AppState, cfg: VendingConfig): IO[Unit] =
    for
      hour <- ScenarioStep.getCurrentHour
      _ <- ScenarioStep.showMenu(cfg, hour)
      product <- ScenarioStep.promptRaw("Название товара")
      (result, log) <- appState.runTransition(
        VendingMachine.selectProduct(product, hour, cfg)
      )
      _ <- ScenarioStep.showLog(log)
      _  <- result match
        case Right(change) if change.isEmpty =>
          IO.putStrLn("  Покупка успешна. Сдача не требуется.")
        case Right(change) =>
          val coins = change.map(Display.formatCoins).mkString(", ")
          IO.putStrLn(s"  Покупка успешна. Сдача: $coins")
        case Left(err) =>
          IO.putStrLn(s"  Покупка отклонена: $err")
    yield ()

  // handleCancel: сценарий отмены - возвращаем внесенные деньги.
  def handleCancel(appState: AppState, cfg: VendingConfig): IO[Unit] =
    for
      (result, log) <- appState.runTransition(VendingMachine.cancelPurchase(cfg))
      _ <- ScenarioStep.showLog(log)
      _ <- result match
        case Right(returned) =>
          IO.putStrLn(s"  Возвращено: ${Display.formatCoins(returned)}")
        case Left(err) =>
          IO.putStrLn(s"  $err")
    yield ()

  // handleRefill: режим пополнения склада.
  // Спрашивает товар и количество, запускает переход refillProduct.

  def handleRefill(appState: AppState, cfg: VendingConfig): IO[Unit] =
    for

      product<- ScenarioStep.promptRaw("Название товара")
      amount <- ScenarioStep.readInt("Количество единиц")

      (result, log) <- appState.runTransition(
        VendingMachine.refillProduct(product, amount, cfg)
      )
      _ <- ScenarioStep.showLog(log)
      _ <- result match
        case Right(newStock) =>
          IO.putStrLn(s"  Новый остаток '$product': $newStock шт.")
        case Left(err) =>
          IO.putStrLn(s"  Ошибка: $err")
    yield ()

  // handleUnknown: неизвестная команда, что-то типо ошибки, но без ошибки
  // просто сообщаем об этом.
  def handleUnknown(cmd: String): IO[Unit] =
    IO.putStrLn(s"  Неизвестная команда: '$cmd'. Введите одну из: buy, insert, cancel, refill, status, menu, quit.")

  // loop: основной рекурсивный цикл.
  //
  // Что делеам:
  //   1. Читаем команду.
  //   2. Выполняем соответствующий обработчик.
  //   3. Если команда не "quit" - рекурсивно вызываем loop снова.
  //
  // Почему рекурсия а не while? Ну... АйЯй сказал, что
  //   "while — это императивный стиль с изменяемой переменной-флагом.
  //   Рекурсия через IO — функциональный эквивалент: описываем «что делать дальше»
  //   как новое IO-значение, не выполняя его сразу."
  //
  // Я ему, можно сказать, поверил, потому здесь и используется рекурсивный цикл.
  def loop(appState: AppState, cfg: VendingConfig): IO[Unit] =

    val commands: Map[String, IO[Unit]] = Map(
      "buy"    -> handleBuy(appState, cfg),
      "insert" -> handleInsert(appState, cfg),
      "cancel" -> handleCancel(appState, cfg),
      "refill" -> handleRefill(appState, cfg),
      "status" -> (for
        s <- appState.get
        _ <- ScenarioStep.showState(s)
      yield ()),
      "menu"   -> (for
        hour <- ScenarioStep.getCurrentHour
        _    <- ScenarioStep.showMenu(cfg, hour)
      yield ()),
      "quit"   -> (for
        s <- appState.get
        _ <- IO.putStrLn("\n  Завершение работы.")
        _ <- ScenarioStep.showState(s)
      yield ())
    )

    for
      cmd <- ScenarioStep.prompt("Команда")

      // commands.get(cmd) возвращает Option[IO[Unit]]:
      //   Some(action) — команда найдена, action — что выполнить
      //   None         — неизвестная команда
      // getOrElse подставляет handleUnknown если команда не найдена.
      // Итого: никакого match, только поиск по ключу + fallback.
      action = commands.get(cmd).getOrElse(handleUnknown(cmd))

      _ <- action

      // Продолжаем цикл если команда не "quit".
      _ <- if cmd == "quit" then IO.pure(()) else loop(appState, cfg)
    yield ()


// --------------------------------------------------------------------------
// 5. MAIN
// --------------------------------------------------------------
//
// Структура:
//   1. Берём конфигурацию из DefaultConfig.
//   2. Создаём AppState с начальным состоянием MachineState.initial.
//   3. Собираем программку - баннер, меню, цикл.
//   4. Вызываем unsafeRun() - вот здесь и происходит грязь.

object Main extends App:

  // Конфигурация автомата - определена в Domain.scala.

  val cfg = DefaultConfig.config

  // Изменяемое состояние.
  val appState = new AppState(MachineState.initial)

  // Собираем полный IO-сценарий:
  //   showBanner - приветствие
  //   getCurrentHour - читаем системное время для скидок
  //   showMenu - показываем меню с ценами
  //   loop - запускаем интерактивный цикл.
  //
  
  // Это просто цепочка описаний "сделай это, потом то" 
  // без реального выполнения.
  val program = //IO[Unit]
    for
      _ <- ScenarioStep.showBanner
      hour <- ScenarioStep.getCurrentHour
      _ <- ScenarioStep.showMenu(cfg, hour)
      _ <- ScenarioLoop.loop(appState, cfg)
    yield ()


  // До этой строки ниже не было напечатано ни одной строки,
  // не прочитано ни одного символа с клавиатуры.
  // Все грязное происходит здесь.
  program.unsafeRun()