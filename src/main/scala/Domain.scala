// =============================================================================
// Блок 1: Reader - конфигурация и чистая логика торгового автомата
// =============================================================================
// Файл содержит:
//   1. VendingConfig - тип окружения (конфигурация автомата)
//   2. DiscountRule       - правило скидки как функция-значение
//   3. Функции на Reader  - priceOf, canAcceptCoin, effectivePrice, calculateChange
//
// Все функции этого блока ЧИСТЫЕ: они не читают глобальных переменных,
// не печатают в консоль, не бросают исключений.
// Вместо этого они возвращают Reader[VendingConfig, A] -
// «обещание вернуть A, когда получишь конфигурацию».
//
// Конфигурация подаётся ОДИН РАЗ - снаружи, в точке запуска сценария.
// =============================================================================

// Импортируем монады и extension methods из Блока 0.
// В реальном проекте это делается через package/import, здесь - концептуально.
// (предполагается, что Monads.scala скомпилирован в том же scope)

// -----------------------------------------------------------------------------
// 1. ТИПЫ КОНФИГУРАЦИИ
// -----------------------------------------------------------------------------

// DiscountRule - правило скидки, выраженное как обычная функция.
// Принимает название товара и текущий час, возвращает множитель цены.
// Множитель 1.0 = нет скидки, 0.9 = скидка 10%, и т.д.
//
// Почему функция, а не case class с полями?
// Функция гибче: правило «сок после 18:00 дешевле на 10%» легко
// записать лямбдой, и такое правило можно передавать как значение.
type DiscountRule = (String, Int) => Double

// VendingConfig - всё окружение торгового автомата.
// Это и есть «Env» в Reader[VendingConfig, A].
//
// Все поля - val, объект неизменяемый.
// Конфигурация создаётся один раз при старте программы и не меняется.
final case class VendingConfig(
                                // Цены на товары: название - цена в копейках (целое число, без Float-проблем).
                                // Например: Map("cola" -> 150, "water" -> 80, "juice" -> 120)
                                prices: Map[String, Int],

                                // Допустимые номиналы монет в копейках.
                                // Например: Set(10, 50, 100, 200, 500, 1000, 2000, 5000)
                                acceptedCoins: Set[Int],

                                // Максимальная сумма, которую автомат держит «внесённой» за раз.
                                // Защита от случайного переполнения кассы.
                                // Например: 10000 (100 рублей)
                                maxInsertable: Int,

                                // Правило скидки - функция (товар, час) => коэффициент.
                                // Передаётся как значение, что позволяет легко менять политику скидок
                                // без изменения логики автомата.
                                discountRule: DiscountRule
                              )

// -----------------------------------------------------------------------------
// 2. ВСПОМОГАТЕЛЬНЫЕ ТИПЫ РЕЗУЛЬТАТОВ
// -----------------------------------------------------------------------------
// Вместо исключений используем Either[String, A]:
//   Right(value) - успех
//   Left(reason) - ошибка с человекочитаемым сообщением
//
// Это стандартный функциональный подход: ошибка - это просто значение,
// а не «выброс» из нормального потока выполнения.

// Псевдоним для удобства чтения типов в сигнатурах.
type Result[A] = Either[String, A]

// Список монет для сдачи - просто список номиналов.
// Например: List(500, 200, 50, 10) означает сдачу 760 копеек.
type Change = List[Int]

// -----------------------------------------------------------------------------
// 3. ФУНКЦИИ БЛОКА 1: READER
// -----------------------------------------------------------------------------
// Каждая функция возвращает Reader[VendingConfig, ???].
// Это значит: «мне нужна конфигурация, чтобы ответить на этот вопрос».
//
// Функции используют Reader.asks - самый прямолинейный способ
// «вытащить» нужное поле из конфигурации.
//
// for-comprehension на Reader работает благодаря:
//   1. given readerMonad (из Блока 0) - обеспечивает flatMap/map для Reader
//   2. extension methods (из Блока 0) - позволяют писать reader.flatMap(...)
// Scala видит `given readerMonad` и автоматически использует его
// при раскрытии for-comprehension в flatMap + map.

object VendingReader:

  // ---------------------------------------------------------------------------
  // priceOf: узнать базовую цену товара из конфигурации.
  //
  // Возвращает Reader[VendingConfig, Result[Int]]:
  //   Right(price) - товар найден, вот его цена
  //   Left(msg)    - такого товара нет в прайсе
  //
  // Почему Result внутри Reader, а не просто Int?
  // Потому что товара может не быть в конфиге - это легитимная ситуация,
  // которую нужно обработать явно, а не упасть с NoSuchElementException.
  // ---------------------------------------------------------------------------
  def priceOf(product: String): Reader[VendingConfig, Result[Int]] =
    // asks извлекает часть конфига одной функцией.
    // cfg.prices.get(product) возвращает Option[Int].
    // toRight превращает Option в Either: None - Left(сообщение), Some(x) - Right(x).
    Reader.asks(cfg =>
      cfg.prices.get(product).toRight(s"Товар '$product' не найден в прайсе")
    )

  // ---------------------------------------------------------------------------
  // canAcceptCoin: проверить, принимает ли автомат монету данного номинала.
  //
  // Возвращает Reader[VendingConfig, Boolean].
  // Простая проверка на вхождение в множество допустимых номиналов.
  // ---------------------------------------------------------------------------
  def canAcceptCoin(coin: Int): Reader[VendingConfig, Boolean] =
    Reader.asks(cfg => cfg.acceptedCoins.contains(coin))

  // ---------------------------------------------------------------------------
  // effectivePrice: рассчитать цену с учётом скидки.
  //
  // Принимает название товара и текущий час (0–23).
  // Применяет discountRule из конфигурации: rule(product, hour) - коэффициент.
  // Результирующая цена округляется вниз до целого числа копеек.
  //
  // Возвращает Result[Int] потому что товара может не быть в прайсе.
  //
  // Пример работы для правила «сок после 18:00 дешевле на 10%»:
  //   effectivePrice("juice", 20).run(config) == Right(108)  // 120 * 0.9
  //   effectivePrice("juice", 12).run(config) == Right(120)  // 120 * 1.0
  // ---------------------------------------------------------------------------
  def effectivePrice(product: String, hour: Int): Reader[VendingConfig, Result[Int]] =
    // Явный Reader(cfg => ...) вместо for-comprehension.
    // for на Reader удобен когда нужно цепочить несколько Reader-вычислений,
    // но здесь достаточно одного обращения к cfg - прямой стиль проще
    // и не вызывает проблем с выводом типов для _.discountRule.
    Reader(cfg =>
      // Шаг 1: ищем базовую цену. get - Option, toRight - Either.
      cfg.prices.get(product)
        .toRight(s"Товар '$product' не найден в прайсе")
        .map(basePrice =>
          // Шаг 2: применяем правило скидки - это функция (String, Int) => Double.
          val multiplier = cfg.discountRule(product, hour)
          // Шаг 3: округляем вниз - честная скидка без округления в пользу автомата.
          (basePrice * multiplier).toInt
        )
    )

  // ---------------------------------------------------------------------------
  // calculateChange: рассчитать сдачу жадным алгоритмом.
  //
  // Принимает: внесённую сумму и цену товара.
  // Возвращает: Reader[VendingConfig, Result[Change]]
  //   Right(coins) - список монет сдачи (жадный алгоритм по убыванию номинала)
  //   Left(msg)    - сдачу дать невозможно (сумма меньше цены)
  //
  // Почему Reader? Потому что допустимые номиналы для сдачи берутся
  // из конфигурации - это тот же acceptedCoins.
  //
  // Жадный алгоритм: берём самую крупную монету, которая помещается
  // в остаток, повторяем пока остаток > 0.
  // Работает корректно для стандартных наборов номиналов (10, 50, 100, ...).
  //
  // Примечание: в Блоке 3 (State) будем учитывать наличие монет в кассе.
  // Здесь - упрощённая версия: считаем, что монеты любого номинала есть.
  // ---------------------------------------------------------------------------
  def calculateChange(inserted: Int, price: Int): Reader[VendingConfig, Result[Change]] =
    Reader.asks(cfg =>
      val changeAmount = inserted - price

      if changeAmount < 0 then
        // Внесено меньше, чем стоит товар - ошибка.
        Left(s"Недостаточно средств: внесено $inserted коп., цена $price коп.")

      else if changeAmount == 0 then
        // Точная сумма - сдача не нужна, возвращаем пустой список.
        Right(List.empty[Int])

      else
        // Сортируем номиналы по убыванию для жадного алгоритма.
        // Например: List(5000, 2000, 1000, 500, 200, 100, 50, 10)
        val sortedCoins = cfg.acceptedCoins.toList.sorted.reverse

        // makeChange - рекурсивная вспомогательная функция.
        // remaining - сколько ещё нужно выдать сдачи
        // coins     - доступные номиналы (уменьшаются по мере исчерпания вариантов)
        // acc       - накопленный список монет сдачи
        //
        // Это хвостовая рекурсия (последний вызов - сам makeChange),
        // Scala оптимизирует её в цикл, переполнения стека не будет.
        def makeChange(remaining: Int, coins: List[Int], acc: Change): Result[Change] =
          if remaining == 0 then
            // Сдача набрана полностью - успех.
            Right(acc.reverse) // reverse: мы добавляли в начало, теперь разворачиваем
          else coins match
            case Nil =>
              // Монеты закончились, а остаток ненулевой - сдачу дать невозможно.
              // Это редкость для стандартных номиналов, но Corner Case важен.
              Left(s"Невозможно выдать сдачу $remaining коп. доступными номиналами")

            case coin :: rest =>
              if coin <= remaining then
                // Эта монета помещается - берём её и уменьшаем остаток.
                // coin :: acc - добавляем в начало (O(1) для списка)
                makeChange(remaining - coin, coins, coin :: acc)
              else
                // Монета слишком крупная - пробуем следующий номинал.
                makeChange(remaining, rest, acc)

        makeChange(changeAmount, sortedCoins, List.empty)
    )

  // ---------------------------------------------------------------------------
  // canInsertMore: проверить, не превышает ли новая монета лимит внесения.
  //
  // Вспомогательная функция - используется в Блоке 3 (State) при insertCoin.
  // Возвращает Reader[VendingConfig, Boolean].
  // ---------------------------------------------------------------------------
  def canInsertMore(currentInserted: Int, coin: Int): Reader[VendingConfig, Boolean] =
    Reader.asks(cfg => currentInserted + coin <= cfg.maxInsertable)

  // ---------------------------------------------------------------------------
  // availableProducts: получить список всех товаров с ценами из конфига.
  //
  // Удобная функция для отображения меню в Блоке 4 (IO).
  // Возвращает отсортированный список пар (название, цена).
  // ---------------------------------------------------------------------------
  def availableProducts: Reader[VendingConfig, List[(String, Int)]] =
    Reader.asks(cfg =>
      cfg.prices.toList.sortBy(_._1) // сортировка по названию товара
    )

// =============================================================================
// Блок 2: Writer - объяснение действий с накоплением лога
// =============================================================================
// Файл содержит функции, которые возвращают Writer[Vector[String], A].
// Каждая функция:
//   1. выполняет вычисление (или фиксирует факт события),
//   2. одновременно накапливает человекочитаемый лог того, что произошло.
//
// Ключевое отличие от простого println:
//   - лог - это ЗНАЧЕНИЕ, он передаётся дальше как данные;
//   - его можно собрать, отфильтровать, распечатать позже;
//   - функции остаются чистыми (нет глобального состояния, нет I/O).
//
// Связь с Блоком 1:
//   Функции Writer принимают уже ВЫЧИСЛЕННЫЕ значения из Reader
//   (цену, результат покупки и т.д.) и объясняют их.
//   Сами они конфигурацию не читают - разделение ответственности.
// =============================================================================

// Псевдоним для краткости: наш стандартный Writer с векторным логом.
// W[A] означает «вычисление, дающее A и накапливающее Vector[String]».
type W[A] = Writer[Vector[String], A]

object VendingWriter:

  // ---------------------------------------------------------------------------
  // Вспомогательная функция: форматировать копейки как рубли.
  // 150 - "1.50 руб."  |  80 - "0.80 руб."  |  5 - "0.05 руб."
  //
  // Приватная - используется только внутри этого объекта.
  // Выделена отдельно, чтобы формат суммы был единообразен во всём логе.
  // ---------------------------------------------------------------------------
  private def formatCoins(amount: Int): String =
    // amount / 100 - рубли (целая часть)
    // amount % 100 - копейки (остаток), %02d - всегда две цифры (05, не 5)
    f"${amount / 100}%d.${amount % 100}%02d руб."

  // ---------------------------------------------------------------------------
  // explainInsert: объяснить внесение монеты.
  //
  // Принимает:
  //   coin           - номинал монеты в копейках
  //   accepted       - была ли монета принята автоматом
  //   currentTotal   - итоговая внесённая сумма ПОСЛЕ этой монеты
  //
  // Возвращает W[Unit] - нет полезного результата, только лог.
  //
  // Почему принимаем `accepted` снаружи, а не проверяем сами?
  // Потому что проверка - это ответственность Reader (Блок 1, canAcceptCoin).
  // Writer только ОБЪЯСНЯЕТ уже принятое решение, а не принимает его заново.
  // ---------------------------------------------------------------------------
  def explainInsert(coin: Int, accepted: Boolean, currentTotal: Int): W[Unit] =
    if accepted then
      // Монета принята - пишем две строки:
      // первая о самом факте, вторая о накопленной сумме.
      Writer((
        (),
        Vector(
          s"[МОНЕТА] Принята монета ${formatCoins(coin)}.",
          s"[ИТОГО ] Внесено: ${formatCoins(currentTotal)}."
        )
      ))
    else
      // Монета отклонена - одна строка с объяснением.
      Writer((
        (),
        Vector(
          s"[ОТКЛОНЕНО] Монета ${formatCoins(coin)} не принимается автоматом."
        )
      ))

  // ---------------------------------------------------------------------------
  // explainPrice: объяснить как формировалась цена товара.
  //
  // Принимает:
  //   product    - название товара
  //   basePrice  - базовая цена из конфига
  //   finalPrice - цена после применения скидки
  //   hour       - час, при котором производился расчёт
  //
  // Возвращает W[Int] - итоговую цену и лог объяснения.
  //
  // Почему возвращаем Int (цену), а не Unit?
  // Потому что результат нужен следующим шагам в цепочке for-comprehension.
  // Writer может нести и значение, и лог одновременно - это его суть.
  // ---------------------------------------------------------------------------
  def explainPrice(product: String, basePrice: Int, finalPrice: Int, hour: Int): W[Int] =
    val discountApplied = finalPrice < basePrice
    val log =
      if discountApplied then
        // Скидка была применена - показываем оба значения и объясняем почему.
        val savedAmount = basePrice - finalPrice
        Vector(
          s"[ЦЕНА  ] Товар: '$product'.",
          s"[ЦЕНА  ] Базовая цена: ${formatCoins(basePrice)}.",
          s"[СКИДКА] Время $hour:00 - действует скидка!",
          s"[СКИДКА] Цена со скидкой: ${formatCoins(finalPrice)} " +
            s"(экономия ${formatCoins(savedAmount)})."
        )
      else
        // Скидки нет - лаконично.
        Vector(
          s"[ЦЕНА  ] Товар: '$product'.",
          s"[ЦЕНА  ] Цена: ${formatCoins(finalPrice)} (скидок нет)."
        )
    // Writer((значение, лог)) - создаём Writer с результатом и логом вместе.
    Writer((finalPrice, log))

  // ---------------------------------------------------------------------------
  // explainPurchase: объяснить результат попытки покупки.
  //
  // Принимает:
  //   product  - название товара
  //   inserted - внесённая сумма
  //   price    - итоговая цена (уже со скидкой)
  //   change   - Result[Change]: Right = сдача успешно рассчитана, Left = ошибка
  //
  // Возвращает W[Result[Change]] - результат покупки с полным объяснением в логе.
  //
  // Лог должен показывать весь путь решения: сумму, цену, сдачу или причину отказа.
  // Именно это требование стоит в задании: «в логе должно быть видно,
  // как формировалась цена и почему покупка прошла или не прошла».
  // ---------------------------------------------------------------------------
  def explainPurchase(
                       product:  String,
                       inserted: Int,
                       price:    Int,
                       change:   Result[Change]
                     ): W[Result[Change]] =
    // Строки, общие для обоих исходов (успех и неудача).
    val header = Vector(
      s"[ПОКУПКА] Товар: '$product'.",
      s"[ПОКУПКА] Внесено: ${formatCoins(inserted)}, цена: ${formatCoins(price)}."
    )

    change match
      case Right(coins) =>
        // Покупка успешна.
        val changeLog =
          if coins.isEmpty then
            // Точная сумма - сдача не нужна.
            Vector(s"[УСПЕХ  ] Точная сумма. Сдача не требуется.")
          else
            // Показываем каждую монету сдачи и итог.
            val changeTotal  = coins.sum
            val coinsFormatted = coins.map(c => formatCoins(c)).mkString(", ")
            Vector(
              s"[УСПЕХ  ] Покупка совершена!",
              s"[СДАЧА  ] Выдаём сдачу: $coinsFormatted.",
              s"[СДАЧА  ] Итого сдачи: ${formatCoins(changeTotal)}."
            )
        // Объединяем заголовок и лог успеха, результат - Right(coins).
        Writer((Right(coins), header ++ changeLog))

      case Left(reason) =>
        // Покупка не прошла - объясняем причину.
        val failLog = Vector(
          s"[ОТКАЗ  ] Покупка не выполнена.",
          s"[ПРИЧИНА] $reason."
        )
        Writer((Left(reason), header ++ failLog))

  // ---------------------------------------------------------------------------
  // explainFailure: объяснить произвольный сбой (не связанный с покупкой).
  //
  // Принимает строку-причину и возвращает W[Unit] с одной строкой в логе.
  //
  // Используется для случаев вне основного потока: неизвестный товар,
  // превышение лимита внесения, пустой склад и т.д.
  // По сути - удобная обёртка над Writer.log для ошибочных ситуаций.
  // ---------------------------------------------------------------------------
  def explainFailure(reason: String): W[Unit] =
    Writer(((), Vector(s"[ОШИБКА ] $reason.")))

  // ---------------------------------------------------------------------------
  // explainCancel: объяснить отмену покупки и возврат монет.
  //
  // Принимает возвращаемую сумму.
  // Возвращает W[Unit] - лог факта отмены.
  //
  // Отмена - самостоятельное событие (не ошибка), поэтому отдельная функция.
  // ---------------------------------------------------------------------------
  def explainCancel(returned: Int): W[Unit] =
    Writer((
      (),
      Vector(
        s"[ОТМЕНА ] Покупка отменена.",
        s"[ВОЗВРАТ] Возвращено: ${formatCoins(returned)}."
      )
    ))

  // ---------------------------------------------------------------------------
  // explainRefill: объяснить пополнение остатков товара.
  //
  // Принимает название товара и добавленное количество единиц.
  // Возвращает W[Unit].
  // ---------------------------------------------------------------------------
  def explainRefill(product: String, amount: Int): W[Unit] =
    Writer((
      (),
      Vector(s"[СКЛАД  ] Пополнено: '$product' +$amount шт.")
    ))

  // ---------------------------------------------------------------------------
  // printLog: отформатировать весь накопленный лог в одну строку для вывода.
  //
  // Вспомогательная функция для Блока 4 (IO): берёт Vector[String] из Writer
  // и превращает его в многострочную строку, готовую для println.
  // ---------------------------------------------------------------------------
  def printLog(log: Vector[String]): String =
    log.mkString("\n")

// -----------------------------------------------------------------------------
// 4. КОНФИГУРАЦИЯ ПО УМОЛЧАНИЮ (для запуска и тестирования)
// -----------------------------------------------------------------------------
// Это не часть «бизнес-логики» - это конкретный экземпляр конфигурации,
// который будет использоваться в сценарии (Блок 4).
//
// Выделено в отдельный объект, чтобы легко подменить в тестах
// или передать другую конфигурацию без изменения логики.

object DefaultConfig:

  // Правило скидки: сок ("juice") после 18:00 дешевле на 10%.
  // Для всех остальных товаров и времён - коэффициент 1.0 (без скидки).
  //
  // Обрати внимание: это обычная функция-значение типа (String, Int) => Double.
  // Мы храним ПРАВИЛО, а не результат - это и есть функциональный подход.
  val discountRule: DiscountRule = (product, hour) =>
    if product == "juice" && hour >= 18 then 0.9
    else 1.0

  // Основная конфигурация автомата.
  // Цены в копейках: 100 коп. = 1 рубль.
  val config: VendingConfig = VendingConfig(
    prices = Map(
      "cola"   -> 150,  // 1.50 руб.
      "water"  ->  80,  // 0.80 руб.
      "juice"  -> 120,  // 1.20 руб. (или 1.08 после 18:00)
      "coffee" -> 200   // 2.00 руб.
    ),
    acceptedCoins = Set(10, 50, 100, 200, 500, 1000, 2000, 5000),
    maxInsertable = 10000, // максимум 100 рублей за раз
    discountRule  = discountRule
  )


// State - изменяемое состояние торгового автомата
//
// Ключевая идея State:
//   Вместо var-переменных каждый переход - это функция S => (A, S).
//   Старое состояние подаётся на вход, новое возвращается вместе с результатом.
//   Никакого глобального мутируемого объекта не существует.


// ---------
// 1. ТИП СОСТОЯНИЯ
// -------------------------------

// MachineState - полный снимок состояния автомата в конкретный момент.
//
// Все поля неизменяемые (val в case class).
// Изменение состояния делается через создание нового объекта через .copy(поле = значение).
final case class MachineState(

                               // Остатки товаров на складе: название - количество единиц.
                               stock: Map[String, Int],

                               inserted: Int,

                               revenue: Int,
                               cashBox: Map[Int, Int]
                             )

object MachineState:
  // Начальное состояние автомата при запуске.
  val initial: MachineState = MachineState(
    stock    = Map("cola" -> 5, "water" -> 5, "juice" -> 5, "coffee" -> 5),
    inserted = 0,
    revenue  = 0,
    // Стартовый набор монет в кассе: достаточно для выдачи сдачи.
    cashBox  = Map(10 -> 20, 50 -> 10, 100 -> 10, 200 -> 5, 500 -> 5)
  )


// -----------------------------------------------------------------------------
// ПЕРЕХОДЫ СОСТОЯНИЯ
// Каждая функция - это переход: она возвращает State[MachineState, ???].
// Внутри она читает старое состояние через State.get/State.gets,
// строит новое через .copy(...), записывает его через State.put/State.modify.


object VendingMachine:

  // Псевдоним для удобства чтения: переход с логом и результатом типа A.
  // SM[A] = State[MachineState, W[Result[A]]]
  // Читается: «переход состояния автомата, дающий объяснённый результат A».
  type SM[A] = State[MachineState, W[Result[A]]]
  
  def insertCoin(coin: Int, cfg: VendingConfig): SM[Int] =

    State.get[MachineState].flatMap(s =>

      // Шаг 1: читаем решения из конфигурации через Reader.
      // .run(cfg) запускает Reader прямо здесь, получая конкретный Boolean.
      val accepted    = VendingReader.canAcceptCoin(coin).run(cfg)
      val withinLimit = VendingReader.canInsertMore(s.inserted, coin).run(cfg)

      if !accepted then {
        // Монета не принимается: состояние не меняем, возвращаем объяснение.
        // State.pure оборачивает значение в State «без изменения состояния».
        
        // summon[Monad[IO]].pure(List.empty[A])) { (action, acc) => // IO.pure(List.empty[A])
        // summon[Monad[State].pure()]
        State.pure(
          VendingWriter.explainInsert(coin, accepted = false, s.inserted)
            // .map преобразует W[Unit] - W[Result[Int]]:
            // результат Unit заменяем на Left с причиной отказа.
            .map(_ => Left(s"Монета ${coin} коп. не принимается автоматом"))
        )
        
        /*summon[Monad[State]].pure(VendingWriter.explainInsert(coin, accepted = false, s.inserted)
          // .map преобразует W[Unit] - W[Result[Int]]:
          // результат Unit заменяем на Left с причиной отказа.
          .map(_ => Left(s"Монета ${coin} коп. не принимается автоматом")))*/

      } else if !withinLimit then
        // Лимит внесения превышен: тоже не меняем состояние.
        State.pure(
          VendingWriter.explainFailure(
            s"Лимит внесения превышен: уже внесено ${s.inserted} коп., " +
              s"монета $coin коп. превысит лимит ${cfg.maxInsertable} коп."
          ).map(_ => Left("Лимит внесения превышен"))
        )

      else
        // Монета принята, строим новое состояние.
        val newInserted = s.inserted + coin
        
        val newCashBox  = s.cashBox.updated(coin, s.cashBox.getOrElse(coin, 0) + 1)

        val newState = s.copy(inserted = newInserted, cashBox = newCashBox)

        // State.put записывает новое состояние, возвращает Unit.
        // flatMap игнорирует Unit и возвращает финальное Writer-значение.
        State.put(newState).flatMap(_ =>
          State.pure(
            VendingWriter.explainInsert(coin, accepted = true, newInserted)
              .map(_ => Right(newInserted))
          )
        )
    )

  // ---------------------------------------------------------------------------
  // selectProduct: выбрать товар и совершить покупку.
  def selectProduct(product: String, hour: Int, cfg: VendingConfig): SM[Change] =
    State.get[MachineState].flatMap(s =>

      // Шаг 1: есть ли товар на складе?
      val stockCount = s.stock.getOrElse(product, 0)
      if stockCount <= 0 then
        State.pure(
          VendingWriter.explainFailure(s"Товар '$product' закончился на складе")
            .map(_ => Left(s"Товар '$product' отсутствует"))
        )

      else
        // Шаг 2: считаем базовую цену и цену со скидкой через Reader.
        val basePriceResult  = VendingReader.priceOf(product).run(cfg)
        val finalPriceResult = VendingReader.effectivePrice(product, hour).run(cfg)

        // Оба результата - Either. Разбираем через match.
        (basePriceResult, finalPriceResult) match

          case (Left(err), _) =>
            // Товар не найден в прайсе конфига - аномалия (есть на складе, нет в ценах).
            State.pure(
              VendingWriter.explainFailure(err)
                .map(_ => Left(err))
            )

          case (Right(basePrice), Right(finalPrice)) =>

            // Шаг 3: пробуем набрать сдачу из реальных монет кассы.
            // Это ключевое отличие от calculateChange в Блоке 1:
            // здесь мы учитываем, сколько монет каждого номинала физически есть.
            val changeAmount = s.inserted - finalPrice
            val changeResult = calculateChangeFromCashBox(changeAmount, s.cashBox)

            // Составляем Writer-объяснение цены (explainPrice возвращает W[Int]).
            val (_, priceLog) = VendingWriter
              .explainPrice(product, basePrice, finalPrice, hour).run

            changeResult match

              case Left(reason) =>
                // Сдачи нет: состояние не меняем.
                val (result, purchaseLog) = VendingWriter
                  .explainPurchase(product, s.inserted, finalPrice, Left(reason)).run
                // Объединяем лог цены и лог покупки вручную.
                State.pure(Writer((result, priceLog ++ purchaseLog)))

              case Right(changeCoins) =>
                // Шаг 4: строим новое состояние.

                // Уменьшаем остаток товара на 1.
                val newStock = s.stock.updated(product, stockCount - 1)

                // Выручка растёт на цену товара.
                // (сдача выдаётся из кассы - она уже там лежит от прошлых монет)
                val newRevenue = s.revenue + finalPrice

                // Убираем монеты сдачи из кассы.
                // foldLeft идёт по каждой монете сдачи и уменьшает счётчик.
                val newCashBox = changeCoins.foldLeft(s.cashBox) { (box, coin) =>
                  val count = box.getOrElse(coin, 0)
                  // Не даём уйти в минус - хотя calculateChangeFromCashBox
                  // гарантирует что монеты есть, защита не лишняя.
                  if count > 0 then box.updated(coin, count - 1)
                  else box
                }

                val newState = s.copy(
                  stock    = newStock,
                  inserted = 0,         // транзакция завершена - сбрасываем внесённое
                  revenue  = newRevenue,
                  cashBox  = newCashBox
                )

                val (result, purchaseLog) = VendingWriter
                  .explainPurchase(product, s.inserted, finalPrice, Right(changeCoins)).run

                State.put(newState).flatMap(_ =>
                  State.pure(Writer((result, priceLog ++ purchaseLog)))
                )

          case (_, Left(err)) =>
            State.pure(
              VendingWriter.explainFailure(err).map(_ => Left(err))
            )
    )

  // ---------------------------------------------------------------------------
  // cancelPurchase: отменить транзакцию и вернуть внесённые деньги.
  def cancelPurchase(cfg: VendingConfig): SM[Int] =
    State.get[MachineState].flatMap(s =>

      val returned = s.inserted

      if returned == 0 then
        // Ничего не было внесено - отменять нечего.
        State.pure(
          VendingWriter.explainFailure("Нет внесённых средств для возврата")
            .map(_ => Left("Нечего возвращать"))
        )
      else
        // Возвращаем монеты: убираем их из кассы жадным алгоритмом.
        // Используем те же номиналы что есть в кассе, отсортированные по убыванию.
        val changeCoins = calculateChangeFromCashBox(returned, s.cashBox)
          .getOrElse(List.empty) // если не смогли набрать точно - возвращаем что есть

        val newCashBox = changeCoins.foldLeft(s.cashBox) { (box, coin) =>
          val count = box.getOrElse(coin, 0)
          if count > 0 then box.updated(coin, count - 1) else box
        }

        val newState = s.copy(inserted = 0, cashBox = newCashBox)

        State.put(newState).flatMap(_ =>
          State.pure(
            VendingWriter.explainCancel(returned)
              .map(_ => Right(returned))
          )
        )
    )

  // ---------------------------------------------------------------------------
  // refillProduct: пополнить запас товара на складе.
  def refillProduct(product: String, amount: Int, cfg: VendingConfig): SM[Int] =
    State.get[MachineState].flatMap(s =>

      if amount <= 0 then
        State.pure(
          VendingWriter.explainFailure(s"Количество пополнения должно быть > 0, получено: $amount")
            .map(_ => Left("Некорректное количество"))
        )

      else if !cfg.prices.contains(product) then
        // Товар неизвестен конфигурации - не знаем куда класть.
        State.pure(
          VendingWriter.explainFailure(s"Неизвестный товар '$product': нет в прайсе")
            .map(_ => Left(s"Товар '$product' неизвестен"))
        )

      else
        val currentStock = s.stock.getOrElse(product, 0)
        val newStock     = currentStock + amount
        val newState     = s.copy(stock = s.stock.updated(product, newStock))

        State.put(newState).flatMap(_ =>
          State.pure(
            VendingWriter.explainRefill(product, amount)
              .map(_ => Right(newStock))
          )
        )
    )

  // ---------------------------------------------------------------------------
  // calculateChangeFromCashBox: набрать сдачу из реальных монет кассы.
  private def calculateChangeFromCashBox(
                                          amount:  Int,
                                          cashBox: Map[Int, Int]
                                        ): Result[Change] =

    if amount < 0 then
      Left(s"Недостаточно средств: не хватает ${-amount} коп.")

    else if amount == 0 then
      Right(List.empty)

    else
      // Сортируем доступные номиналы по убыванию.
      // Берём только те, которых есть хотя бы 1 штука.
      val available = cashBox.toList
        .filter(_._2 > 0)
        .sortBy(-_._1) // сортировка по убыванию номинала

      // Рекурсивный жадный алгоритм с учётом количества монет.
      def go(remaining: Int, coins: List[(Int, Int)], acc: Change): Result[Change] =
        if remaining == 0 then
          Right(acc.reverse)
        else coins match
          case Nil =>
            Left(s"Недостаточно монет в кассе для сдачи $remaining коп.")

          case (denomination, count) :: rest =>
            if denomination > remaining then
              // Монета слишком крупная - пробуем следующий номинал.
              go(remaining, rest, acc)
            else
              // Берём столько монет этого номинала сколько нужно,
              // но не больше чем есть в кассе.
              // needed - сколько таких монет идеально нужно
              val needed = remaining / denomination
              val take   = needed min count  // min: не берём больше чем есть
              // Добавляем take копий номинала в аккумулятор.
              // List.fill(take)(denomination) создаёт список из take одинаковых элементов.
              val newAcc       = List.fill(take)(denomination) ::: acc
              val newRemaining = remaining - take * denomination
              // Переходим к следующему номиналу с обновлённым остатком.
              go(newRemaining, rest, newAcc)

      go(amount, available, List.empty)