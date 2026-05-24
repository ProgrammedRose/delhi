
trait ConfigAlgebra[F[_]]:

  // Базовая цена товара из прайса.
  def priceOf(product: String): F[Result[Int]]

  // Проверить допустимость номинала монеты.
  def canAcceptCoin(coin: Int): F[Boolean]

  // Цена с учётом скидки на конкретный час.
  def effectivePrice(product: String, hour: Int): F[Result[Int]]

  // Рассчитать сдачу по номиналам из конфига (без учёта реальной кассы).
  def calculateChange(inserted: Int, price: Int): F[Result[Change]]

  // Проверить, можно ли внести ещё одну монету (не превысит ли лимит).
  def canInsertMore(currentInserted: Int, coin: Int): F[Boolean]

  // Список всех товаров с базовыми ценами - для отображения меню.
  def availableProducts: F[List[(String, Int)]]


// LogAlgebra[F[_]] - накопление и чтение лога
  
// В ЛР1 за лог отвечал Writer[Vector[String], A].
// Каждая функция возвращала W[A] = Writer[Vector[String], A],
// и лог автоматически накапливался через flatMap.
//
// В Tagless Final лог - это эффект, скрытый за F[_].
// Интерпретатор сам решает хранить лог в Writer, в изменяемом буфере
// внутри IO, или вообще игнорировать (для тестов где лог не важен).
//
// Методы соответствуют функциям VendingWriter из ЛР1,
// но возвращают F[Unit] вместо W[Unit] - тупо запиши это.
// Получить накопленный лог можно через getLogs.
// ------------------------
trait LogAlgebra[F[_]]:

  // Записать произвольное сообщение в лог.
  // Базовая операция - все explain-методы строятся на ней.
  def log(message: String): F[Unit]

  // Объяснить внесение монеты.
  // Соответствует VendingWriter.explainInsert из ЛР1.
  // accepted - была ли монета принята
  // total - сколько внесено суммарно после этой операции
  def explainInsert(coin: Int, accepted: Boolean, total: Int): F[Unit]

  // Объяснить формирование цены (базовая vs итоговая со скидкой).
  // Соответствует VendingWriter.explainPrice из ЛР1.
  def explainPrice(product: String, base: Int, final_ : Int, hour: Int): F[Unit]

  // Объяснить результат покупки - успех или причину отказа.
  // Соответствует VendingWriter.explainPurchase из ЛР1.
  def explainPurchase(
                       product:      String,
                       inserted:     Int,
                       price:        Int,
                       changeResult: Result[Change]
                     ): F[Unit]

  // Объяснить отказ произвольной операции.
  // Соответствует VendingWriter.explainFailure из ЛР1.
  def explainFailure(reason: String): F[Unit]

  // Объяснить отмену покупки и возврат средств.
  // Соответствует VendingWriter.explainCancel из ЛР1.
  def explainCancel(returned: Int): F[Unit]

  // Объяснить пополнение склада.
  // Соответствует VendingWriter.explainRefill из ЛР1.
  def explainRefill(product: String, amount: Int): F[Unit]

  // Получить весь накопленный лог.
  // В Writer-интерпретаторе это извлечение лога из Writer.
  // В IO-интерпретаторе - чтение из внутреннего буфера.
  // Нужен для вывода лога в конце операции (Scenario.scala).
  def getLogs: F[Vector[String]]

  // Сбросить лог - очистить накопленное перед новой операцией.
  // Нужен чтобы лог каждой операции был отдельным, а не накапливался
  // бесконечно за всё время работы программы.
  def clearLogs: F[Unit]


// -----------------------------------------------------------------------------
// 3. MachineAlgebra[F[_]] - переходы состояния автомата
// -----------------------------------------------------------------------------
// В ЛР1 за переходы отвечал VendingMachine (Блок 3).
// Каждый переход возвращал State[MachineState, W[Result[A]]].
//
// В Tagless Final мы убираем State и Writer из сигнатур.
// Метод возвращает просто F[Result[A]] - «вычисление с результатом».
// Что именно скрыто внутри F (State? IO? что-то ещё?) - дело интерпретатора.
//
// Лог вынесен в LogAlgebra: переходы сами не пишут в лог,
// вместо этого программа явно вызывает LogAlgebra после каждого перехода.
// Это делает зависимости явными и упрощает тестирование.
//
// Параметр cfg убран из сигнатур: интерпретатор получает конфигурацию
// при создании (в конструкторе), а не при каждом вызове метода.
// -----------------------------------------------------------------------------
trait MachineAlgebra[F[_]]:

  // Внести монету в автомат.
  // Result[Int]: Right(новая суммарная сумма) или Left(причина отказа).
  // Соответствует VendingMachine.insertCoin из ЛР1.
  def insertCoin(coin: Int): F[Result[Int]]

  // Выбрать товар и совершить покупку.
  // Result[Change]: Right(список монет сдачи) или Left(причина отказа).
  // hour нужен для расчёта скидки (из ConfigAlgebra.effectivePrice).
  // Соответствует VendingMachine.selectProduct из ЛР1.
  def selectProduct(product: String, hour: Int): F[Result[Change]]

  // Отменить транзакцию и вернуть внесённые деньги.
  // Result[Int]: Right(возвращённая сумма) или Left("нечего возвращать").
  // Соответствует VendingMachine.cancelPurchase из ЛР1.
  def cancelPurchase: F[Result[Int]]

  // Пополнить склад товаром.
  // Result[Int]: Right(новый остаток) или Left(причина отказа).
  // Соответствует VendingMachine.refillProduct из ЛР1.
  def refillProduct(product: String, amount: Int): F[Result[Int]]

  // Прочитать текущее состояние автомата.
  // Нужен для отображения статуса в Scenario.scala.
  // В ЛР1 это делалось через AppState.get - здесь инкапсулировано в алгебру.
  def getState: F[MachineState]

// -----------------------------------------------------------------------------
// 4. ConsoleAlgebra[F[_]] — консольный ввод-вывод
// -----------------------------------------------------------------------------
// В ЛР1 за консоль отвечали IO.putStrLn / IO.readLine напрямую в Scenario.scala.
// Здесь абстрагируемся: ScenarioLoop не знает про конкретный IO,
// он работает с любым F у которого есть ConsoleAlgebra.
// -----------------------------------------------------------------------------
trait ConsoleAlgebra[F[_]]:
  def putStrLn(s: String): F[Unit]
  def putStr(s: String): F[Unit]
  def readLine: F[String]
// -----------------------------------------------------------------------------
// ПРИМЕЧАНИЕ: почему три отдельных алгебры, а не одна?
// -----------------------------------------------------------------------------
// Можно было бы собрать все методы в один trait VendingAlgebra[F[_]].
// Но разделение на три даёт важные преимущества:
//
// 1. Принцип разделения интерфейсов: программа, которой нужен только
//    лог, получает только LogAlgebra - не весь автомат целиком.
//
// 2. Разные интерпретаторы для разных алгебр: ConfigAlgebra удобно
//    интерпретировать через Reader, LogAlgebra - через Writer или IO,
//    MachineAlgebra - через State. Смешивать их в одном trait было бы
//    неудобно.
//
// 3. Тестируемость: в тесте на логику покупки можно подать реальный
//    MachineAlgebra но заглушку LogAlgebra - и не думать про лог.
//
// В программе (Program.scala) все три алгебры собираются вместе:
//   def vendingProgram[F[_]: Monad](
//     machine: MachineAlgebra[F],
//     config:  ConfigAlgebra[F],
//     logger:  LogAlgebra[F]
//   ): F[Unit]
  