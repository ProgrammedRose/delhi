
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


// --------------------------------------------
// 3. MachineAlgebra[F[_]] - переходы состояния автомата
// В ЛР1 за переходы отвечал VendingMachine (это Блок 3).
trait MachineAlgebra[F[_]]:

  // Внести монету в автомат.
  def insertCoin(coin: Int): F[Result[Int]]

  // Выбрать товар и совершить покупку.
  def selectProduct(product: String, hour: Int): F[Result[Change]]

  // Отменить транзакцию и вернуть внесённые деньги.
  def cancelPurchase: F[Result[Int]]

  // Пополнить склад товаром.
  def refillProduct(product: String, amount: Int): F[Result[Int]]

  // Прочитать текущее состояние автомата.
  def getState: F[MachineState]

// -----------------------------------------------------------------------------
// 4. ConsoleAlgebra[F[_]] - консольный ввод-вывод
// В первой лабе за консоль отвечали IO.putStrLn / IO.readLine напрямую в Main.
// Здесь ScenarioLoop не знает про конкретный IO,
// он работает с любым F у которого есть ConsoleAlgebra.
// -----------------------------------------------------------------------------
trait ConsoleAlgebra[F[_]]:
  def putStrLn(s: String): F[Unit]
  def putStr(s: String): F[Unit]
  def readLine: F[String]
